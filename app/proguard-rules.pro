# SPDX-FileCopyrightText: 2026 Roboparty Camp
# SPDX-License-Identifier: GPL-3.0-only

# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName *;
}

# 保持通信类不被混淆
-keep class com.roboparty.rpoexpression.communication.** { *; }
-keep class com.roboparty.rpoexpression.expression.** { *; }
