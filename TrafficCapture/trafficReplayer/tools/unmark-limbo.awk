# SPDX-License-Identifier: Apache-2.0
#
# Reconstructs the original source of a REBUILD-LIMBO-marked file.
#
#   awk -f tools/unmark-limbo.awk path/to/Marked.java
#
# Verified against every marked file in this module by tools/verify-limbo-markers.sh: every code line
# comes back exactly, in order, with nothing added or dropped. That check is what makes the marking
# trustworthy -- carried code is only safe to mark if getting it back is mechanical rather than a
# judgment call.
#
# It is *not* byte-identical, and cannot be made so. Marking pads a region with a blank line after the
# opening delimiter and before the closing one, and such a blank is indistinguishable from an original
# one: in a mid-file region the blank before `*/` is usually the blank that separated two members, so a
# rule that stripped it would lose real content. The padding is unguarded, so it is not reversible --
# which is precisely why AGENTS.md section 8a requires a guard for anything else the marker rewrites.
# The verifier therefore compares ignoring blank lines, and that is the property to rely on.
#
# This is deliberately not the normal way to promote code. Promoting a member means deleting the
# markers around it and leaving the rest marked, which keeps blame on every line. This script is for
# the whole-file case and for verifying that nothing was lost in the marking.
#
# Markers may be indented, because a member-level region sits at the indentation of the member it
# wraps. A `*/` is treated as a region closer only while a region is open, so an indented javadoc
# closer -- which looks identical at the start of a line -- is never mistaken for one.
#
# A region may carry a `//` note between its START and its `/*`, naming what blocks that specific
# member. Those notes are dropped on reconstruction exactly like the whole-file header is; they are
# commentary about the marking, not carried code.
#
# Javadoc is never inside a region, so it needs no unescaping: Java does not nest block comments, and
# escaping javadoc to survive an enclosing region is both lossy to reverse and destroys its blame.
# Only non-javadoc block comments inside implementation are guarded, and that guard is one prefix.

/^[[:space:]]*\/\/ REBUILD-LIMBO\(/              { inhdr = 1; next }
inhdr && /^[[:space:]]*\/\/ /                    { next }
inhdr                                            { inhdr = 0 }

/^[[:space:]]*\/\/ REBUILD-LIMBO-START\(/        { expect_open = 1; next }
expect_open && /^[[:space:]]*\/\*[[:space:]]*$/  { expect_open = 0; inregion = 1; next }
expect_open && /^[[:space:]]*\/\//               { next }

inregion && /^[[:space:]]*\*\/[[:space:]]*$/     { inregion = 0; pending_close = $0; next }
/^[[:space:]]*\/\/ REBUILD-LIMBO-END\(/          { pending_close = ""; next }
pending_close != ""                              { print pending_close; pending_close = "" }

/^[[:space:]]*\/\/ REBUILD-LIMBO-ESCAPED-LINE\(/ {
    sub(/\/\/ REBUILD-LIMBO-ESCAPED-LINE\(G[0-9]+\): /, "")
    print
    next
}

{ print }

END { if (pending_close != "") print pending_close }
