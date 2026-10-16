//! JniOutput:经 Kotlin `AudioBridge` 托管的 AudioTrack 输出后端。
//!
//! 设计(CLAUDE.md D2 修订):M2 先用 AudioTrack via JNI —— 稳定、零额外原生依赖,
//! 引擎线程按块推送,阻塞写天然提供背压与播放节奏;低延迟 oboe/AAudio 后端
//! 留待后续里程碑,经 `AudioOutput` trait 可无缝替换。
//!
//! 线程约定:本后端只在引擎线程被调用,每次调用临时 attach JVM(每秒几十次,开销可忽略)。

use std::sync::Arc;

use jni::objects::{GlobalRef, JClass, JObject, JValue};
use jni::JavaVM;
use log::warn;

use player_core::{AudioOutput, OutputError};

/// Kotlin 侧桥接类的 JNI 路径。
pub(crate) const BRIDGE_CLASS: &str = "com/hirust/player/audio/AudioBridge";

/// Android 输出后端:Rust → Kotlin 静态方法 → AudioTrack。
pub struct JniOutput {
    vm: Arc<JavaVM>,
    class: GlobalRef,
}

impl JniOutput {
    /// 以共享句柄构造(engineStart 时与事件泵共享同一份)。
    pub(crate) fn new(vm: Arc<JavaVM>, class: GlobalRef) -> Self {
        Self { vm, class }
    }
}

impl AudioOutput for JniOutput {
    fn start(&mut self, sample_rate: u32, channels: u8) -> Result<(), OutputError> {
        let result = (|| -> jni::errors::Result<bool> {
            let mut env = self.vm.attach_current_thread()?;
            let class: &JClass<'_> = self.class.as_obj().into();
            env.call_static_method(
                class,
                "outputStart",
                "(II)Z",
                &[
                    JValue::Int(sample_rate as i32),
                    JValue::Int(i32::from(channels)),
                ],
            )
            .and_then(|v| v.z())
        })();

        match result {
            Ok(true) => Ok(()),
            Ok(false) => Err(OutputError::UnsupportedFormat {
                sample_rate,
                channels,
            }),
            Err(e) => {
                warn!("outputStart JNI 调用失败: {e}");
                Err(OutputError::Io(format!("outputStart: {e}")))
            }
        }
    }

    fn write(&mut self, block: &[f32]) -> Result<(), OutputError> {
        let result = (|| -> jni::errors::Result<i32> {
            let mut env = self.vm.attach_current_thread()?;
            let class: &JClass<'_> = self.class.as_obj().into();
            let arr = env.new_float_array(block.len() as i32)?;
            env.set_float_array_region(&arr, 0, block)?;
            let obj = JObject::from(arr);
            env.call_static_method(class, "outputWrite", "([F)I", &[JValue::Object(&obj)])
                .and_then(|v| v.i())
        })();

        match result {
            Ok(n) if n >= 0 => Ok(()),
            Ok(n) => Err(OutputError::Io(format!("AudioTrack 写入错误码: {n}"))),
            Err(e) => {
                warn!("outputWrite JNI 调用失败: {e}");
                Err(OutputError::Io(format!("outputWrite: {e}")))
            }
        }
    }

    fn suspend(&mut self) -> Result<(), OutputError> {
        call_void(self, "outputSuspend")
    }

    fn resume(&mut self) -> Result<(), OutputError> {
        call_void(self, "outputResume")
    }

    fn stop(&mut self) -> Result<(), OutputError> {
        call_void(self, "outputStop")
    }
}

fn call_void(out: &JniOutput, method: &str) -> Result<(), OutputError> {
    let result = (|| -> jni::errors::Result<()> {
        let mut env = out.vm.attach_current_thread()?;
        let class: &JClass<'_> = out.class.as_obj().into();
        env.call_static_method(class, method, "()V", &[])
            .and_then(|v| v.v())
    })();

    result.map_err(|e| {
        warn!("{method} JNI 调用失败: {e}");
        OutputError::Io(format!("{method}: {e}"))
    })
}
