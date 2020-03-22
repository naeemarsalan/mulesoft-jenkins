#!/bin/bash
# Check MS3 Naming Convention

# Return code defentition
rc_0=0
rc_1=0
rc_2=0
rc_3=0

function rc {
    case "$1" in
        0) echo "+++++  OKAY  $2"; ((rc_0++));;
        1) echo "~~~~~  WARN  $2"; ((rc_1++));;
        2) echo "-----  ERR!  $2"; ((rc_2++));;
        *) echo "?!??!  WATT  $2"; ((rc_3++));;
    esac
}

# Make sure README.md is in place
if [ ! -e "README.md" ]; then
    rc 2 'README.md file found'
else
    rc 0 'README.md is in place'

    # Check README.md size
    readme_lines=$(wc -l README.md |awk '{print $1}')
    if [[ "$readme_lines" -lt 30 ]]; then
        rc 1 'README.md looks too short / consider adding more inforamation'
    else
        rc 0 "README.md has $readme_lines lines. Looks promising"
    fi
fi

# Check munit tests
if [ ! -d "src/test/munit" ]; then
    rc 2 "munit test directory not found"
else
    rc 0 "munit test directory has been found"
    if [ -z $(ls src/test/munit/*.xml) ]; then
        rc 2 "no tests found"
    else
        rc 0 "$(ls src/test/munit/*.xml| head -n1) exists"
    fi
fi


# Check pom.xml file
if [ ! -e "pom.xml" ]; then
    rc 2 'pom.xml not found'
else
    rc 0 'pom.xml is in place'
    app_name=$(grep '<name>' pom.xml |head -n1 |cut -f2 -d'>' |cut -f1 -d'<')
fi

# Check uppercase in the name
if [[ "$app_name" =~ [A-Z] ]]; then
    rc 2 "$app_name should be all lowercase"
else
    rc 0 "$app_name name is in lowercase"
fi

# Check undersrcores in the name
if [[ "$app_name" =~ [_] ]]; then
    rc 2 "$app_name should use hyphens instead of underscores"
else
    rc 0 "$app_name no undersrcores in the name"
fi

# Check dots in the name
if [[ "$app_name" =~ [.] ]]; then
    rc 2 "$app_name should use hyphens instead of dots"
else
    rc 0 "$app_name no dots in the name"
fi

# Check for known abbriviations
if ! [[ "$app_name" =~ (.*-sys-.*|.*-proc-.*|.*-exp-.*) ]]; then
    rc 2 "$app_name should use sys, proc or expr as abbreviation"
else
    rc 0 "$app_name [sys|proc|exp] abbreviation check passed"
fi

# Check for api in the name
if ! [[ "$app_name" =~ .*-api$ ]]; then
    rc 2 "$app_name should have -api in the end"
else
    rc 0 "$app_name -api check passed"
fi

# Check repo name is the same as app name
echo "GIT_URL =  $GIT_URL"
env
if [ -e "$GIT_URL" ]; then
    repo_name=$(basename $GIT_URL |cut -f 1 -d .)
fi

if [[ "$app_name" != "$repo_name" ]]; then
    rc 2 "Git repo $repo_name should match the app name"
else
    rc 0 "Git $repo_name repository name check passed"
fi

# Check Jenkins job name
if [ -e "$JOB_BASE_NAME" ] && [[ "$repo_name" =~ "$JOB_BASE_NAME" ]]; then
    rc 2 "Jenkins $JOB_BASE_NAME should include repository name"
else
    rc 0 "Jenkins job $JOB_BASE_NAME matches git repo name"
fi

# Check artifactId
artifact=$(grep 'artifactId>' pom.xml |head -n1 |cut -f2 -d'>' |cut -f1 -d'<')
if [ "$app_name" != "$artifact" ]; then
    rc 2 "$artifact artifact doesn't match application name"
else
    rc 0 "artifactId matches application name"
fi

# Check dev property file
if [ ! -e "src/main/resources/dev-properties.yaml" ]; then
    rc 2 "dev-properties.yaml not found"
else
    rc 0 "dev-properties.yaml is in place"
fi

# Check prod property file
if [ ! -e "src/main/resources/prod-properties.yaml" ]; then
    rc 2 "prod-properties.yaml not found"
else
    rc 0 "prod-properties.yaml is in place"
fi

# Check log4j2 application name
if [ -z "$(grep fileName src/main/resources/log4j2.xml |grep $app_name)" ]; then
    rc 2 "log4j2 fileName should coincide with the application name"
else
    rc 0 "log4j2 log name check passed"
fi

# Check flowfilename
if [ ! -e "src/main/mule/$app_name.xml" ]; then
    rc 2 "src/main/mule/$app_name.xml not found"
fi

# Check listener name
listener="/api/$(echo $app_name| sed 's/-api//')"
if [ -z "$(grep listener src/main/mule/*.xml| grep $listener)" ]; then
    rc 2 "Listener $listener not found"
else
    rc 0 "Listener $listener is in place"
fi


# Print report
echo "================"
echo "Passed: $rc_0; Warnings: $rc_1; Errors: $rc_2; Unknowns: $rc_3"
echo '================
        \   ^__^
         \  (oo)\_______
            (__)\       )\/\
                ||----w |
                ||     ||'
