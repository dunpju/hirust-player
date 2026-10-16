//! 标签/封面读取集成测试(WAV 无标签、无封面,验证时长与错误路径)。

mod common;

use common::{temp_wav, write_sine_wav};
use player_core::metadata::{read_cover, read_tags};

#[test]
fn wav_tags_duration_without_metadata() {
    let path = temp_wav("meta1");
    write_sine_wav(&path, 48_000, 500);

    let tags = read_tags(&path).unwrap();
    assert_eq!(tags.title, None);
    assert_eq!(tags.artist, None);
    assert_eq!(tags.album, None);
    assert_eq!(tags.duration_ms, 500);

    assert!(read_cover(&path).unwrap().is_none());
    let _ = std::fs::remove_file(&path);
}

#[test]
fn missing_file_is_error() {
    assert!(read_tags(std::path::Path::new("/nonexistent/x.wav")).is_err());
    assert!(read_cover(std::path::Path::new("/nonexistent/x.wav")).is_err());
}
