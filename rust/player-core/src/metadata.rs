//! 标签与内嵌封面读取:ID3v2 / VorbisComment / FLAC 等经 symphonia 统一读取。
//!
//! 独立于 [`crate::decoder`]:本模块只做一次性探测读取(probe 即返回),
//! 不持有解码状态。probe 过程与 decoder 有小段重复,为保持两侧职责单一而接受。

use std::fs::File;
use std::path::Path;

use symphonia::core::codecs::CODEC_TYPE_NULL;
use symphonia::core::formats::{FormatOptions, FormatReader};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::meta::{MetadataLog, StandardTagKey, StandardVisualKey, Tag, Value, Visual};
use symphonia::core::probe::Hint;

use crate::error::CoreError;

/// 文本标签(全部可选,时长必填)。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TrackTags {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    /// 时长(毫秒,由总帧数与采样率推得;流式来源为 0)。
    pub duration_ms: u64,
}

/// 内嵌封面(编码字节,如 JPEG/PNG)。
pub struct Cover {
    pub media_type: String,
    pub data: Vec<u8>,
}

/// 读取曲目文本标签与时长。
pub fn read_tags(path: &Path) -> Result<TrackTags, CoreError> {
    let (mut format, mut log) = probe(path)?;
    let mut tags = TrackTags::default();

    if let Some(track) = format
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != CODEC_TYPE_NULL)
    {
        let params = &track.codec_params;
        if let (Some(frames), Some(rate)) = (params.n_frames, params.sample_rate) {
            tags.duration_ms = frames * 1000 / u64::from(rate);
        }
    }

    for_each_tag(&mut format, &mut log, |tag| {
        let std_matches = |k: StandardTagKey| tag.std_key == Some(k);
        let key = tag.key.to_ascii_lowercase();
        let assign = |slot: &mut Option<String>, value: &str| {
            if slot.is_none() && !value.is_empty() {
                *slot = Some(value.to_owned());
            }
        };
        let Value::String(raw) = &tag.value else {
            return;
        };
        let value = raw.trim();
        if std_matches(StandardTagKey::TrackTitle) || key == "title" {
            assign(&mut tags.title, value);
        } else if std_matches(StandardTagKey::Artist) || key == "artist" {
            assign(&mut tags.artist, value);
        } else if std_matches(StandardTagKey::Album) || key == "album" {
            assign(&mut tags.album, value);
        }
    });

    Ok(tags)
}

/// 读取内嵌封面;优先正面封面(FrontCover),其次任意图片。
pub fn read_cover(path: &Path) -> Result<Option<Cover>, CoreError> {
    let (mut format, mut log) = probe(path)?;
    let mut front: Option<Cover> = None;
    let mut any: Option<Cover> = None;

    let mut visit = |v: &Visual| {
        let cover = Cover {
            media_type: v.media_type.clone(),
            data: v.data.to_vec(),
        };
        if v.usage == Some(StandardVisualKey::FrontCover) {
            front.get_or_insert(cover);
        } else {
            any.get_or_insert(cover);
        }
    };

    if let Some(log) = log.as_mut() {
        if let Some(rev) = log.metadata().current() {
            for visual in rev.visuals() {
                visit(visual);
            }
        }
    }
    if let Some(rev) = format.metadata().current() {
        for visual in rev.visuals() {
            visit(visual);
        }
    }

    Ok(front.or(any))
}

fn probe(path: &Path) -> Result<(Box<dyn FormatReader>, Option<MetadataLog>), CoreError> {
    let file = File::open(path).map_err(|e| CoreError::Open(format!("{}: {e}", path.display())))?;
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

    Ok((probed.format, probed.metadata.into_inner()))
}

fn for_each_tag(
    format: &mut Box<dyn FormatReader>,
    log: &mut Option<MetadataLog>,
    mut f: impl FnMut(&Tag),
) {
    if let Some(log) = log.as_mut() {
        if let Some(rev) = log.metadata().current() {
            for tag in rev.tags() {
                f(tag);
            }
        }
    }
    if let Some(rev) = format.metadata().current() {
        for tag in rev.tags() {
            f(tag);
        }
    }
}
