#!/bin/bash
# Download one Harare map tile from each candidate provider, the way the app's WebView would
# (Android WebView user agent, no Referer), so the results can be looked at.
UA="Mozilla/5.0 (Linux; Android 14; SM-A546B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36"
Z=15; X=19210; Y=18033
mkdir -p out
get() { # name url
  code=$(curl -sS -m 30 -A "$UA" -H "X-Requested-With: zw.robowatch.app" -o "out/$1.png" -w '%{http_code} %{content_type} %{size_download}' "$2" 2>&1)
  echo "$1 | $code | $2" | tee -a out/results.txt
}
get osm            "https://tile.openstreetmap.org/$Z/$X/$Y.png"
get osm_fr_hot     "https://a.tile.openstreetmap.fr/hot/$Z/$X/$Y.png"
get osm_fr_osmfr   "https://a.tile.openstreetmap.fr/osmfr/$Z/$X/$Y.png"
get esri_street    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$Z/$Y/$X"
get esri_light     "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/$Z/$Y/$X"
get esri_dark      "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$Z/$Y/$X"
get carto_light    "https://a.basemaps.cartocdn.com/light_all/$Z/$X/$Y.png"
get osm_de         "https://tile.openstreetmap.de/$Z/$X/$Y.png"
get cyclosm        "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/$Z/$X/$Y.png"
