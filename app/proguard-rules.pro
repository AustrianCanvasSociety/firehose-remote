# Rules for the release build's R8 pass, which is on via
# `minifyEnabled true` in app/build.gradle.

# Shrinking without renaming: a user's stack trace names the class and method
# that failed, which is the point — a renamed trace needs a mapping file that
# an F-Droid install does not ship.
-dontobfuscate

# Keep the positions as well as the names. Without these, a demangled trace
# has class and method names but no source file or line numbers, and the two
# `javaClass.simpleName` log lines lose their real class names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
