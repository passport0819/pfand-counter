# Shrinking keeps the download small now that AppCompat and Material Components are inside.
# The optional local deposit source is loaded by name (ExtraSource), so R8 must keep it.
-keep class app.pfandcounter.LocalSource { <init>(); *; }
# No renaming: crash reports and logcat stay readable, and the app has nothing to hide.
-dontobfuscate
