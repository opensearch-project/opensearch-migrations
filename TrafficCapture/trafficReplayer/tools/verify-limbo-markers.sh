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
unrecovered=0
duplicated=0
whole_file_checked=0
body_checked=0

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

    # Property 2c: no marked code line goes MISSING from the reconstruction. Checked without history and
    # therefore for every file, including the partially marked ones the comparison below cannot cover -- their
    # regions were added across several commits, so there is no single "before" to diff against, and those are
    # precisely the files where recovery is least mechanical.
    #
    # What this does and does not prove, stated exactly because the summary line used to overclaim it. The
    # comparison is a sorted multiset difference, so it detects a line that vanishes. It does NOT detect
    # reordering, and it does not detect a duplicate, because both leave the set of present lines unchanged.
    # A set comparison is the most that is available here: body lines like a bare `}` also occur in live code,
    # so an order-preserving check would have to decide which occurrence is which, and would report false
    # failures on every file. Order and duplication are covered exactly, by diff, for the whole-file-marked
    # files below -- which is 220 of 226 -- and the line-count check that follows bounds duplication for the
    # rest.
    #
    # Escaped lines are excluded: the awk rewrites them by design, so they are not expected to appear
    # unchanged. Blank lines are excluded for the same reason as below.
    bodies=$(awk '
        /^[[:space:]]*\/\/ REBUILD-LIMBO-START\(/ { inregion = 1; next }
        /^[[:space:]]*\/\/ REBUILD-LIMBO-END\(/ { inregion = 0; inbody = 0; next }
        inregion && !inbody && $0 ~ /^[[:space:]]*\/\*[[:space:]]*$/ { inbody = 1; next }
        inregion && inbody && $0 ~ /^[[:space:]]*\*\/[[:space:]]*$/ { inbody = 0; next }
        inregion && inbody && $0 ~ /REBUILD-LIMBO-ESCAPED-LINE/ { next }
        inregion && inbody && $0 !~ /^[[:space:]]*$/ { print }
    ' "$file" | sort)
    if [ -n "$bodies" ]; then
        lost=$(comm -23 <(printf '%s\n' "$bodies") <(awk -f "$AWK" "$file" | grep -v '^[[:space:]]*$' | sort))
        if [ -n "$lost" ]; then
            echo "UNRECOVERED: $file has marked code line(s) missing from its reconstruction"
            printf '%s\n' "$lost" | head -8 | sed 's/^/  /'
            unrecovered=$((unrecovered + 1))
        fi
        body_checked=$((body_checked + 1))

        # Bounds duplication, which the set comparison above cannot see. Reconstruction only ever deletes
        # lines, so its output must be no longer than the input; a longer output means the awk emitted
        # something twice.
        input_lines=$(grep -cv '^[[:space:]]*$' "$file")
        output_lines=$(awk -f "$AWK" "$file" | grep -cv '^[[:space:]]*$')
        if [ "$output_lines" -gt "$input_lines" ]; then
            echo "DUPLICATED: $file reconstructs to $output_lines non-blank lines from $input_lines"
            duplicated=$((duplicated + 1))
        fi
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
echo "  no marked code line missing, checked directly for $body_checked file(s) with region bodies"
echo "  order and duplication proved exactly, by diff against history, for $whole_file_checked whole-file-marked file(s)"
failures=$((malformed + unbalanced + residue + drifted + unrecovered + duplicated))
if [ "$failures" -ne 0 ]; then
    echo "FAIL: $unbalanced unbalanced, $malformed malformed, $residue with residue, $drifted drifted," \
         "$unrecovered with unrecovered lines, $duplicated with duplicated lines"
    exit 1
fi
echo "PASS: markers well-formed, reconstruction clean, no drift."
