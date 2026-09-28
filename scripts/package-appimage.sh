#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "Usage: $0 <compose-distribution> <output.AppImage> <appimagetool> <runtime>" >&2
    exit 2
fi

distribution_dir=$1
output_file=$2
appimagetool=$3
appimage_runtime=$4
project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

launcher="$distribution_dir/bin/SimpleLector"
icon="$project_dir/desktop-assets/icon.png"
desktop_entry="$project_dir/packaging/appimage/com.lextrack.simplelector.desktop"
app_run="$project_dir/packaging/appimage/AppRun"
metainfo="$project_dir/packaging/appimage/com.lextrack.simplelector.appdata.xml"

for required_file in "$launcher" "$icon" "$desktop_entry" "$app_run" "$metainfo" "$appimagetool" "$appimage_runtime"; do
    if [[ ! -f "$required_file" ]]; then
        echo "Required AppImage input is missing: $required_file" >&2
        exit 1
    fi
done

staging_dir=$(mktemp -d)
trap 'rm -rf "$staging_dir"' EXIT
app_dir="$staging_dir/SimpleLector.AppDir"

mkdir -p \
    "$app_dir/usr/lib" \
    "$app_dir/usr/share/applications" \
    "$app_dir/usr/share/icons/hicolor/1024x1024/apps" \
    "$app_dir/usr/share/metainfo"

cp -a "$distribution_dir" "$app_dir/usr/lib/SimpleLector"
install -m 755 "$app_run" "$app_dir/AppRun"
install -m 644 "$desktop_entry" "$app_dir/com.lextrack.simplelector.desktop"
install -m 644 "$desktop_entry" "$app_dir/usr/share/applications/com.lextrack.simplelector.desktop"
install -m 644 "$icon" "$app_dir/simplelector.png"
install -m 644 "$icon" "$app_dir/usr/share/icons/hicolor/1024x1024/apps/simplelector.png"
install -m 644 "$metainfo" "$app_dir/usr/share/metainfo/com.lextrack.simplelector.appdata.xml"
ln -s simplelector.png "$app_dir/.DirIcon"

mkdir -p "$(dirname "$output_file")"
output_dir=$(cd "$(dirname "$output_file")" && pwd)
output_file="$output_dir/$(basename "$output_file")"

ARCH=x86_64 "$appimagetool" --appimage-extract-and-run \
    --runtime-file "$appimage_runtime" \
    "$app_dir" \
    "$output_file"

if [[ ! -s "$output_file" ]]; then
    echo "AppImage was not created: $output_file" >&2
    exit 1
fi

chmod 755 "$output_file"
(
    cd "$staging_dir"
    "$output_file" --appimage-extract >/dev/null
    test -x squashfs-root/AppRun
)

echo "AppImage created: $output_file"
