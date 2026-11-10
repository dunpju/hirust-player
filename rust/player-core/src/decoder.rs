//! 基于 symphonia 的音频解码:probe、逐块解码为交织 f32、时长与 seek。

use std::fs::File;
use std::path::Path;

use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::{DecoderOptions, CODEC_TYPE_NULL};
use symphonia::core::errors::Error as SymError;
use symphonia::core::formats::{FormatOptions, FormatReader, Packet, SeekMode, SeekTo};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;
use symphonia::core::units::TimeBase;

use crate::error::CoreError;

/// 媒体基本信息。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaInfo {
    pub sample_rate: u32,
    pub channels: u8,
    /// 总时长(毫秒,由总帧数与采样率推得;流式来源可能为 0)。
    pub duration_ms: u64,
    /// 编码器名称(调试用)。
    pub codec: String,
}

/// 单文件解码器:一个实例对应一个音频文件。
///
/// 位置由最近一个包的时间戳(track time_base)推得,seek 后自动校准。
pub struct AudioDecoder {
    format: Box<dyn FormatReader>,
    decoder: Box<dyn symphonia::core::codecs::Decoder>,
    track_id: u32,
    time_base: TimeBase,
    info: MediaInfo,
    pos_ms: u64,
    sample_buf: Option<SampleBuffer<f32>>,
    /// 最近一次创建 sample_buf 用的格式(rate, 声道数)。
    buf_key: Option<(u32, usize)>,
    /// open 预探测时缓存的首包(重放进 next_block,样本零丢失)。
    pending: Option<Packet>,
}

impl AudioDecoder {
    /// 打开并探测音频文件,创建解码器。
    pub fn open(path: &Path) -> Result<Self, CoreError> {
        let file =
            File::open(path).map_err(|e| CoreError::Open(format!("{}: {e}", path.display())))?;
        let mss = MediaSourceStream::new(Box::new(file), Default::default());

        let mut hint = Hint::new();
        if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
            hint.with_extension(ext);
        }

        let probed = symphonia::default::get_probe()
            .format(
                &hint,
                mss,
                &FormatOptions::default(),
                &MetadataOptions::default(),
            )
            .map_err(|e| CoreError::Open(format!("{}: {e}", path.display())))?;
        let mut format = probed.format;

        // 在轨道借用作用域内创建解码器并拷出所需标量,解除 format/track 借用。
        let (track_id, time_base, mut rate, mut channels, n_frames, codec_desc, mut decoder) = {
            let track = format
                .tracks()
                .iter()
                .find(|t| t.codec_params.codec != CODEC_TYPE_NULL)
                .ok_or_else(|| CoreError::Open(format!("{}: 未找到音频轨", path.display())))?;
            // 注意:AAC 等解码器的 codec_params() 返回入参原样(ASC 信息只
            // 存在于解码缓冲的 spec),参数缺失时须**实际解码一个包**才能取到
            // 权威采样率/声道(m4a/录音实测)。该包克隆缓存,next_block 重放
            // (多解码一次代价可忽略,样本零丢失);参数齐全不触发,行为不变。
            let decoder = symphonia::default::get_codecs()
                .make(&track.codec_params, &DecoderOptions::default())
                .map_err(|e| CoreError::Open(format!("{}: 创建解码器失败: {e}", path.display())))?;
            let p = &track.codec_params;
            let tb = p
                .time_base
                .ok_or_else(|| CoreError::Open(format!("{}: 缺少 time_base", path.display())))?;
            (
                track.id,
                tb,
                p.sample_rate,
                p.channels.map(|c| c.count()),
                p.n_frames,
                format!("{:?}", p.codec),
                decoder,
            )
        };

        let mut pending: Option<Packet> = None;
        if rate.is_none() || channels.is_none() {
            loop {
                let packet = format.next_packet().map_err(|e| {
                    CoreError::Open(format!("{}: 预解码读取失败: {e}", path.display()))
                })?;
                if packet.track_id() != track_id {
                    continue;
                }
                let decoded = decoder
                    .decode(&packet)
                    .map_err(|e| CoreError::Open(format!("{}: 预解码失败: {e}", path.display())))?;
                let spec = *decoded.spec();
                rate = Some(spec.rate);
                channels = Some(spec.channels.count());
                pending = Some(packet);
                break;
            }
            // 重放同一包前复位解码器,丢弃探测期的半解码状态。
            decoder.reset();
        }
        let rate =
            rate.ok_or_else(|| CoreError::Open(format!("{}: 缺少采样率", path.display())))?;
        let channels =
            channels.ok_or_else(|| CoreError::Open(format!("{}: 缺少声道信息", path.display())))?;
        let duration_ms = n_frames.map(|n| n * 1000 / u64::from(rate)).unwrap_or(0);

        let info = MediaInfo {
            sample_rate: rate,
            channels: channels as u8,
            duration_ms,
            codec: codec_desc,
        };

        Ok(Self {
            track_id,
            format,
            decoder,
            time_base,
            info,
            pos_ms: 0,
            sample_buf: None,
            buf_key: None,
            pending,
        })
    }

    /// 媒体信息。
    pub fn info(&self) -> &MediaInfo {
        &self.info
    }

    /// 当前解码位置(毫秒,块起点)。
    pub fn position_ms(&self) -> u64 {
        self.pos_ms
    }

    /// 解码下一块交织 f32 样本;`Ok(None)` 表示曲目结束。
    ///
    /// 返回的切片在下次调用前有效;坏包会被跳过。
    pub fn next_block(&mut self) -> Result<Option<&[f32]>, CoreError> {
        loop {
            // open 阶段预探测缓存的首包优先重放(样本零丢失)。
            let packet = match self.pending.take() {
                Some(p) => p,
                None => match self.format.next_packet() {
                    Ok(p) => p,
                    // symphonia 以 UnexpectedEof 表示自然结束。
                    Err(SymError::IoError(ref e))
                        if e.kind() == std::io::ErrorKind::UnexpectedEof =>
                    {
                        return Ok(None)
                    }
                    Err(e) => return Err(CoreError::Decode(e.to_string())),
                },
            };

            if packet.track_id() != self.track_id {
                continue;
            }

            // 由包时间戳校准位置(seek 后即由这里修正)。
            let ts = packet.ts();
            self.pos_ms =
                u64::from(self.time_base.numer) * ts * 1000 / u64::from(self.time_base.denom);

            let decoded = match self.decoder.decode(&packet) {
                Ok(d) => d,
                // 坏包:丢弃并继续。
                Err(SymError::DecodeError(_)) => continue,
                Err(e) => return Err(CoreError::Decode(e.to_string())),
            };

            let spec = *decoded.spec();
            let key = (spec.rate, spec.channels.count());
            let cap = self.sample_buf.as_ref().map_or(0, |b| b.capacity() as u64);
            if self.buf_key != Some(key) || decoded.capacity() as u64 > cap {
                self.sample_buf = Some(SampleBuffer::<f32>::new(decoded.capacity() as u64, spec));
                self.buf_key = Some(key);
            }

            match self.sample_buf.as_mut() {
                Some(buf) => {
                    buf.copy_interleaved_ref(decoded);
                    return Ok(Some(buf.samples()));
                }
                None => return Err(CoreError::Decode("内部样本缓冲未初始化".into())),
            }
        }
    }

    /// 在当前曲目内 seek(毫秒)。
    pub fn seek_ms(&mut self, ms: u64) -> Result<(), CoreError> {
        let ts = (u64::from(self.time_base.denom) * ms)
            / (u64::from(self.time_base.numer) * 1000).max(1);
        self.format
            .seek(
                SeekMode::Accurate,
                SeekTo::TimeStamp {
                    ts,
                    track_id: self.track_id,
                },
            )
            .map_err(|e| CoreError::Decode(format!("seek 失败: {e}")))?;
        // seek 后必须复位解码器,丢弃半解码状态;预探测首包随之作废。
        self.decoder.reset();
        self.pending = None;
        self.pos_ms = ms;
        Ok(())
    }
}
