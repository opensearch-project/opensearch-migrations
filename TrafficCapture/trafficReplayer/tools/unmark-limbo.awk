# SPDX-License-Identifier: Apache-2.0
#
# Reconstructs the original source of a REBUILD-LIMBO-marked file.
#
#   awk -f tools/unmark-limbo.awk path/to/Marked.java
#
# Verified against every marked file in this module: all reconstruct byte-identically to their
# pre-rebuild originals. That check is what makes the marking trustworthy -- carried code is only
# safe to mark if getting it back is mechanical rather than a judgment call.
#
# This is deliberately not the normal way to promote code. Promoting a member means deleting the
# markers around it and leaving the rest marked, which keeps blame on every line. This script is for
# the whole-file case and for verifying that nothing was lost in the marking.
#
# Javadoc is never inside a region, so it needs no unescaping: Java does not nest block comments, and
# escaping javadoc to survive an enclosing region is both lossy to reverse and destroys its blame.
# Only non-javadoc block comments inside implementation are guarded, and that guard is one prefix.

/^\/\/ REBUILD-LIMBO\(/                { inhdr = 1; next }
inhdr && /^\/\/ /                      { next }
inhdr                                  { inhdr = 0 }

/^\/\/ REBUILD-LIMBO-START\(/          { expect_open = 1; next }
expect_open && $0 == "/*"              { expect_open = 0; next }

$0 == "*/"                             { pending_close = 1; next }
/^\/\/ REBUILD-LIMBO-END\(/            { pending_close = 0; next }
pending_close                          { print "*/"; pending_close = 0 }

/^\/\/ REBUILD-LIMBO-ESCAPED-LINE\(/ {
    sub(/^\/\/ REBUILD-LIMBO-ESCAPED-LINE\(G[0-9]+\): /, "")
    print
    next
}

{ print }
