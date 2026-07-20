cd app/release
apksigner verify --print-certs app-release.apk

CMD=$1
TAG=$2
if [ "$CMD" == "release" ] ; then
    zip app-release.apk.zip app-release.apk
    gh release upload $TAG app-release.apk.zip
fi

if [ "$CMD" == "remove" ] ; then
    gh release delete-asset $TAG app-release.apk.zip -y
fi
