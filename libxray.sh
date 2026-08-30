#!/bin/bash
set -e
export ANDROID_NDK_HOME=${NDK_HOME}

git clone -b main https://github.com/xtls/xray-core.git ./xray-core
patch --verbose -p1 --directory=xray-core < xray.patch
#patch --verbose -p1 --directory=AndroidLibXrayLite < libv2ray.patch
cp trimgeo.go ./xray-core
cd ./xray-core
curl -sL https://github.com/any116/v2ray-rules-dat/releases/latest/download/geosite.dat -o geosite_cut.dat
go run trimgeo.go -in geosite_cut.dat -keep cn,private,google -out ../AndroidLibXrayLite/assets/geosite.dat
rm -rf trimgeo.go geosite_cut.dat
#curl -sL "https://github.com/XTLS/Xray-core/pull/6275.diff" | patch --verbose -p1
#curl -sL "https://github.com/XTLS/Xray-core/pull/6053.diff" | patch --verbose -p1
#curl -sL "https://github.com/XTLS/Xray-core/pull/6005.diff" | patch --verbose -p1
tar --exclude=.git -czf ../xray-core.tar.gz * .[!.]* .??*
cd ../AndroidLibXrayLite

git apply ../libv2ray.patch

curl -sL https://raw.githubusercontent.com/Loyalsoldier/geoip/release/geoip-only-cn-private.dat -o assets/geoip.dat
curl -sL https://raw.githubusercontent.com/Loyalsoldier/geoip/release/geoip-only-cn-private.dat -o assets/geoip-only-cn-private.dat

echo -e "\nreplace github.com/xtls/xray-core => ../xray-core" >> go.mod
sed -i 's/^go 1\.26\(\.[0-9]\+\)\?/go 1.26/' go.mod

go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
#go get -u

#v=$(curl -s "https://api.github.com/repos/grpc/grpc-go/branches?per_page=100" | grep -o '"sha": "[^"]*"' | tail -n1 | cut -d'"' -f4); 
#rpc_ver=$(curl -s "https://raw.githubusercontent.com/grpc/grpc-go/$v/go.mod" | grep -m1 'google.golang.org/genproto/googleapis/rpc' | awk '{print $2}');
#[ -n "$v" ] && go get google.golang.org/grpc@$v || echo "错误：获取分支版本失败" >&2
#[ -n "$rpc_ver" ] && go get google.golang.org/genproto/googleapis/rpc@"$rpc_ver" || echo "错误：获取rpc_ver版本失败" >&2

go get \
    golang.org/x/mobile@latest \
    github.com/sagernet/sing@v0.5.1 \
    github.com/sagernet/sing-shadowsocks@v0.2.7 \
    gvisor.dev/gvisor@go
go mod tidy -v

tar --exclude=.git -czf ../xraylite_android.tar.gz * .[!.]* .??*
gomobile bind -v -androidapi 24 -target android/arm64 -trimpath -ldflags="-s -w -buildid=" ./

echo "Sucess build androidlibxraylite.aar"
