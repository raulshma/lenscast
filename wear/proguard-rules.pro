# LensCast Wear R8 rules.
#
# Nothing bespoke yet: OkHttp, DataStore, and Compose all ship consumer
# rules inside their artifacts, and the wear module parses JSON through the
# platform org.json classes (kept by the AOSP stubs' own rules). Reflection
# is not used anywhere in this source set, so the default optimize config
# applies cleanly. Add rules here only if a release build breaks at runtime.
