# ProGuard/R8 rules — BuzzBike
#
# Release builds currently ship with isMinifyEnabled=false (app/build.gradle.kts).
# This file is referenced so the build type is complete; rules land here when
# minification is enabled (after slice 5+, once domain/data are stable).
#
# Do NOT add -keep rules for domain/ formulas to "make them work" — if R8
# strips something incorrectly, that's a bug to fix with proper keep rules
# for the specific API surface, not blanket keeps.

# Add project-specific ProGuard rules below.
