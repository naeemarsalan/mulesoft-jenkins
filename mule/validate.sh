#!/bin/bash
ApplicationList=$(anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=$anypoint_env runtime-mgr standalone-application list -o json)
echo $ApplicationList
echo $server_name
echo $server_name
if [[ $ApplicationList =~ $artifact ]];
then
{
	echo "The application exists in the list"
	while [ "$status" != "STARTED" ]
	do 
    time="$(anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application describe -o json ${artifact} | $jq -r '.Updated')"
    echo TIME = ${time}
    response="$(anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application describe -o json ${artifact})"
	echo ${response}
    status="$(anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application describe -o json ${artifact} | $jq -r '.Status')"
	echo STATUS = ${status}
    if [ "$status" = "DEPLOYMENT_FAILED" ];
	then
    	if [ "$time" = "a few seconds ago" ];
		then
			echo "The deployment failed"
        	exit 1
        fi
    elif [ "$status" = "STARTED" ];
	then
		echo TIME2 = ${time}
    	if [ "$time" != "in a minute" ] && [ "$time" != "in a few seconds" ] && [ "$time" != "a few seconds ago" ] && [ "$time" != "a minute ago" ];
		then
			echo TIME3 = ${time}
			echo "not redeployed"
            exit 1
        else
			echo TIME4 = ${time}
        	echo "deployment successful"
		fi
	fi
    if [ "$status" = "UNDEPLOYED" ];
    then
        echo "Application is Undeployed"
        exit 1
    fi
	done
}
else
{
	echo "no such application found"
	exit 1
}
fi