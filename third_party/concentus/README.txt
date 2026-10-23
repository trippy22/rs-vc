Concentus source used by RS Voice Chat

Upstream: https://github.com/lostromb/concentus
Input: user-provided concentus-master archive, Java/Concentus/src/main/java/org/concentus.
The archive has no Git metadata. provenance.json pins every imported source file
by its original and vendored SHA-256 hashes; it does not claim a Git revision.
The upstream README describes this Java port as based on libopus 1.1.2.

117 source files are compiled under src/main/java/org/concentus, retaining their
original package and copyright/license headers. The full single-stream Opus
decoder, including SILK, hybrid and CELT processing, remains. No native code or
binary library is included. No reference-tree files are needed at build/runtime.

Omitted unused source files:
ChannelLayout.java, OpusMSDecoder.java, OpusMSEncoder.java, OpusMultistream.java,
SpeexResampler.java, TOCStruct.java, VorbisLayout.java.

Only local codec edit:
Bands.java: remove idx1/idx2 temporary variables and the two conditional
System.out.println("e") diagnostics that used them. The following bit-interleave
calculation and all codec arithmetic are unchanged. Other files match upstream
hashes exactly. The plugin's profile settings are in com.rsvc.OpusCodec, outside
the vendored sources.

License: LICENSE here and META-INF/CONCENTUS-LICENSE.txt in the built JAR.
Per-file notices: original headers retained in source, also collected without
duplicate headers in META-INF/CONCENTUS-NOTICES.txt for binary distribution.
RS VC's root license does not replace these third-party notices.

verifyVendoredCodec runs before compileJava to validate the source set, source
hashes, license and collected notices. Deliberate future edits must be documented
here and in provenance.json; never update hashes just to silence a failed check.

tools/codec-audit.ps1 can reproduce the original whole-file subset experiment
when the reference archive and former Maven JAR are available locally. It is not
required to build the plugin. The normal Java tests exercise the production
profile and all decoder modes; wireTest exercises the production codec/transport.
