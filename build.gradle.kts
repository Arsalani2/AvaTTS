import java.util.Properties

// Top-level build file where you can add configuration options common to all sub-projects/modules.
// BUG-30: نسخهٔ اپ از version.properties ریشه خوانده می‌شود؛ ماژول app از extra می‌گیرد.
val versionPropsFile = file("version.properties")
val versionProps = Properties().apply {
  if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}
ext {
  set("appVersionCode", (versionProps.getProperty("versionCode", "1")).toInt())
  set("appVersionName", versionProps.getProperty("versionName", "1.0"))
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  // BUG-30: google-services حذف شد — Firebase در کد استفاده نمی‌شود
}
