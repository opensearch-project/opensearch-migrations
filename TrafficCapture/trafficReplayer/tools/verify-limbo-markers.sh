#!/bin/bash
#
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#
# Proves the two properties REBUILD-LIMBO marking depends on, per AGENTS.md section 8a.
#
#   1. Marked code is inert, not merely unused. Every REBUILD-LIMBO-START is immediately followed by a
#      bare `/*` and every REBUILD-LIMBO-END is immediately preceded by a bare `*/`, so javac never sees
#      the region. A malformed pair is the one way marked code could go on compiling -- or could stop the
#      file compiling at all -- so it is the thing worth checking mechanically.
#   2. Un-marking is mechanical. Reconstruction through unmark-limbo.awk leaves no marker behind, and for
#      a whole-file-marked file every code line comes back exactly, in order, compared against the
#      content that was marked as recovered from the commit that marked it.
#
#      That comparison ignores blank lines, deliberately and not as a convenience. Marking pads each
#      region with a blank after the opening delimiter and before the closing one, and those blanks are
#      unguarded -- indistinguishable from original ones, since the blank before a mid-file `*/` is
#      usually the separator between two members. Stripping them by rule would lose real content, so
#      reconstruction is exact on code and approximate on padding. Any difference this check does report
#      is therefore a real lost, added, or altered line.
#
# Usage: tools/verify-limbo-markers.sh [module-src-dir]
# Exits nonzero on the first class of failure found, listing every instance of it.

set -u -o pipefail

cd "$(dirname "$0")/../../.." || exit 2
SRC="${1:-TrafficCapture/trafficReplayer/src}"
AWK="TrafficCapture/trafficReplayer/tools/unmark-limbo.awk"

if [ ! -f "$AWK" ]; then
    echo "FAIL: cannot find $AWK" >&2
    exit 2
fi

marked=$(grep -rl 'REBUILD-LIMBO-START' "$SRC" | sort)
if [ -z "$marked" ]; then
    echo "No marked files under $SRC. Nothing to verify -- the rebuild is complete by this measure."
    exit 0
fi

marked_count=$(printf '%s\n' "$marked" | wc -l | tr -d ' ')
malformed=0
unbalanced=0
residue=0
drifted=0
whole_file_checked=0

for file in $marked; do
    starts=$(grep -c '^[[:space:]]*// REBUILD-LIMBO-START(' "$file")
    ends=$(grep -c '^[[:space:]]*// REBUILD-LIMBO-END(' "$file")
    if [ "$starts" -ne "$ends" ]; then
        echo "UNBALANCED: $file has $starts START and $ends END"
        unbalanced=$((unbalanced + 1))
    fi

    # Property 1: a START must open a comment on the very next line, and an END must close one on the
    # line before it. awk reports each offending line number so the failure is directly addressable.
    # A START may be followed by a `//` note naming what blocks that member before the `/*` opens.
    bad=$(awk '
        awaiting_open && $0 ~ /^[[:space:]]*\/\*[[:space:]]*$/ { awaiting_open = 0; next }
        awaiting_open && $0 ~ /^[[:space:]]*\/\// { next }
        awaiting_open {
            print "  line " NR ": START reaches a non-comment line before its /* opens"
            awaiting_open = 0
        }
        /^[[:space:]]*\/\/ REBUILD-LIMBO-START\(/ { awaiting_open = 1; next }
        /^[[:space:]]*\/\/ REBUILD-LIMBO-END\(/ && last !~ /^[[:space:]]*\*\/[[:space:]]*$/ {
            print "  line " NR ": END not preceded by a bare */"
        }
        { last = $0 }
        END { if (awaiting_open) print "  end of file: START never opened its /*" }
    ' "$file")
    if [ -n "$bad" ]; then
        echo "MALFORMED: $file"
        echo "$bad"
        malformed=$((malformed + 1))
    fi

    # Property 2a: reconstruction leaves no marker behind. Matches marker syntax rather than any mention
    # of the word, because live prose legitimately refers to REBUILD-LIMBO and must survive.
    left=$(awk -f "$AWK" "$file" \
        | grep -c '^[[:space:]]*// REBUILD-LIMBO\(-START\|-END\|-ESCAPED-LINE\|-NOTE\)\?(')
    if [ "$left" -ne 0 ]; then
        echo "RESIDUE: $file leaves $left marker line(s) after reconstruction"
        awk -f "$AWK" "$file" \
            | grep -n '^[[:space:]]*// REBUILD-LIMBO\(-START\|-END\|-ESCAPED-LINE\|-NOTE\)\?(' | sed 's/^/  /'
        residue=$((residue + 1))
    fi

    # Property 2b: for a whole-file-marked file, reconstruction must reproduce exactly what was marked.
    # The commit that added the whole-file header is the marking commit, so its parent holds the original.
    if grep -q 'nothing in this file is live yet' "$file"; then
        marking_commit=$(git log --format=%H -S'nothing in this file is live yet' --max-count=1 -- "$file" 2>/dev/null)
        if [ -n "$marking_commit" ] && git cat-file -e "$marking_commit^:$file" 2>/dev/null; then
            whole_file_checked=$((whole_file_checked + 1))
            # Both sides go through the reconstruction. If the parent was unmarked source the awk is a
            # no-op on it; if the file was already partially marked before being marked whole, the parent
            # carries its own markers and notes, which reconstruction strips from both sides alike. Without
            # this, re-marking an already-marked file reports the old scaffolding as lost code.
            if ! diff -B -q \
                <(git show "$marking_commit^:$file" | awk -f "$AWK") \
                <(awk -f "$AWK" "$file") >/dev/null; then
                echo "DRIFTED: $file loses or alters a code line versus $marking_commit^"
                diff -B <(git show "$marking_commit^:$file" | awk -f "$AWK") <(awk -f "$AWK" "$file") \
                    | head -12 | sed 's/^/  /'
                drifted=$((drifted + 1))
            fi
        fi
    fi
done

echo
echo "Checked $marked_count marked file(s) under $SRC."
echo "  every code line recovered, verified against history for $whole_file_checked whole-file-marked file(s)"
failures=$((malformed + unbalanced + residue + drifted))
if [ "$failures" -ne 0 ]; then
    echo "FAIL: $unbalanced unbalanced, $malformed malformed, $residue with residue, $drifted drifted"
    exit 1
fi
echo "PASS: markers well-formed, reconstruction clean, no drift."
