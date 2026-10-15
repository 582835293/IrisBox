#!/usr/bin/env bash
# Run at the existing AOSP root; preserve stock source AND stock build output.
set -eo pipefail
module=packages/modules/Connectivity
patch_file=$(readlink -f "$1")
result=$(readlink -m "$2")
test -f "$patch_file"
test -z "$(git -C "$module" status --porcelain)"
git -C "$module" apply --check "$patch_file"
mkdir "$result"
cp "$patch_file" "$result/guest_inet.patch"
git -C "$module" rev-parse HEAD > "$result/source_commit.txt"
source build/envsetup.sh > /dev/null
lunch aosp_rpi4-userdebug > "$result/lunch.log" 2>&1
output=out/target/product/rpi4/system/apex/com.android.tethering/lib64/libservice-connectivity.so
test -f "$output"
sha256sum "$output" > "$result/stock_before.sha256"
applied=0
cleanup() {
    code=$?
    trap - EXIT
    if [ "$applied" = 1 ]; then
        git -C "$module" apply -R "$result/guest_inet.patch" || exit 10
        m libservice-connectivity -j4 > "$result/stock_build.log" 2>&1 || exit 11
        test -z "$(git -C "$module" status --porcelain)" || exit 12
        sha256sum "$output" > "$result/stock_after.sha256"
        test "$(cut -d ' ' -f 1 "$result/stock_before.sha256")" = "$(cut -d ' ' -f 1 "$result/stock_after.sha256")" || exit 13
        echo STOCK_SOURCE_AND_OUTPUT_RESTORED_IDENTICALLY > "$result/restoration.txt"
    fi
    exit "$code"
}
trap cleanup EXIT
git -C "$module" apply "$result/guest_inet.patch"
applied=1
git -C "$module" diff --check
if ! m libservice-connectivity -j4 > "$result/candidate_build.log" 2>&1; then
    tail -n 70 "$result/candidate_build.log"
    exit 4
fi
cp -p "$output" "$result/libservice-connectivity_guest_inet.so"
sha256sum "$result/libservice-connectivity_guest_inet.so"
