#!/bin/bash
# 单页验证：参数 name url，输出 "name,http_code,title"
name="$1"; url="$2"
tmp=$(mktemp)
code=$(curl -sL --max-time 40 -A "Mozilla/5.0" -o "$tmp" -w "%{http_code}" "$url")
title=$(grep -o '<title>[^<]*</title>' "$tmp" 2>/dev/null | head -1 | sed 's/<[^>]*>//g')
rm -f "$tmp"
echo "$name,$code,\"$title\""
