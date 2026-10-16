//! 测试公共工具:程序化生成 WAV 样本,避免在仓库里提交二进制 fixtures。

use std::fs::File;
use std::io::Write;
use std::path::{Path, PathBuf};

/// 生成单声道 16-bit PCM 440Hz 正弦波 WAV。
pub fn write_sine_wav(path: &Path, rate: u32, ms: u64) {
    let frames = (u64::from(rate) * ms / 1000) as usize;
    let mut data = Vec::with_capacity(frames * 2);
    for i in 0..frames {
        let t = i as f32 / rate as f32;
        let v = (2.0 * std::f32::consts::PI * 440.0 * t).sin() * 12_000.0;
        data.extend_from_slice(&(v as i16).to_le_bytes());
    }

    let mut buf = Vec::with_capacity(44 + data.len());
    buf.extend_from_slice(b"RIFF");
    buf.extend_from_slice(&(36 + data.len() as u32).to_le_bytes());
    buf.extend_from_slice(b"WAVE");
    buf.extend_from_slice(b"fmt ");
    buf.extend_from_slice(&16u32.to_le_bytes());
    buf.extend_from_slice(&1u16.to_le_bytes()); // PCM
    buf.extend_from_slice(&1u16.to_le_bytes()); // 单声道
    buf.extend_from_slice(&rate.to_le_bytes());
    buf.extend_from_slice(&(rate * 2).to_le_bytes()); // byte rate
    buf.extend_from_slice(&2u16.to_le_bytes()); // block align
    buf.extend_from_slice(&16u16.to_le_bytes()); // 位深
    buf.extend_from_slice(b"data");
    buf.extend_from_slice(&(data.len() as u32).to_le_bytes());
    buf.extend_from_slice(&data);

    let mut f = File::create(path).unwrap();
    f.write_all(&buf).unwrap();
}

/// 本测试专用临时 WAV 路径(按测试名区分)。
pub fn temp_wav(tag: &str) -> PathBuf {
    let mut p = std::env::temp_dir();
    p.push(format!("hirust-m1-{tag}-{}.wav", std::process::id()));
    p
}
