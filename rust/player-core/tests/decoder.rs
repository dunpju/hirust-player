//! AudioDecoder 集成测试(WAV 样本程序化生成)。

mod common;

use std::path::Path;

use common::{temp_wav, write_sine_wav};
use player_core::{AudioDecoder, CoreError};

#[test]
fn probe_and_decode_wav_to_end() {
    let path = temp_wav("dec1");
    write_sine_wav(&path, 44_100, 1000);

    let mut dec = AudioDecoder::open(&path).unwrap();
    let info = dec.info().clone();
    assert_eq!(info.sample_rate, 44_100);
    assert_eq!(info.channels, 1);
    assert!(
        (950..=1050).contains(&info.duration_ms),
        "duration = {}",
        info.duration_ms
    );

    let mut frames = 0u64;
    let mut nonzero = false;
    while let Some(block) = dec.next_block().unwrap() {
        frames += (block.len() / usize::from(info.channels)) as u64;
        if block.iter().any(|s| s.abs() > 0.1) {
            nonzero = true;
        }
    }
    assert_eq!(frames, 44_100, "总帧数应等于 1s × 44100");
    assert!(nonzero, "正弦波不应全为静音");
    // EOF 后继续解码应稳定返回 None。
    assert!(dec.next_block().unwrap().is_none());

    let _ = std::fs::remove_file(&path);
}

#[test]
fn seek_updates_position_and_continues() {
    let path = temp_wav("dec2");
    write_sine_wav(&path, 44_100, 1000);

    let mut dec = AudioDecoder::open(&path).unwrap();
    assert!(dec.next_block().unwrap().is_some());

    dec.seek_ms(500).unwrap();
    let block = dec.next_block().unwrap().expect("seek 后应继续解码");
    assert!(!block.is_empty());
    assert!(
        (450..=700).contains(&dec.position_ms()),
        "pos = {}",
        dec.position_ms()
    );

    let _ = std::fs::remove_file(&path);
}

#[test]
fn open_missing_file_is_open_error() {
    let err = match AudioDecoder::open(Path::new("/nonexistent/hirust.wav")) {
        Err(e) => e,
        Ok(_) => panic!("打开不存在的文件应失败"),
    };
    assert!(matches!(err, CoreError::Open(_)));
}
