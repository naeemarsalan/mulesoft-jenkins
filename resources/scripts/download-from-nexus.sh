mkdir target
curl -sSL -X GET -G "http://maven.ms3-inc.com/service/rest/v1/search/assets" \
	-d repository=$(basename "${nexusUrl}") \
	-d maven.groupId=$groupName \
	-d maven.artifactId=$artifactName \
	-d maven.baseVersion=$version \
	-d maven.extension=jar \
    -d maven.classifier="${packaging}" \
| grep -Po '"downloadUrl" : "\K.+(?=",)' \
| tail -1 | xargs curl -fsSL -o target/${artifactName}-${version}-${packaging}.jar