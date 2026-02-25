Pod::Spec.new do |spec|
    spec.name                     = 'googledrivebackup'
    spec.version                  = '1.0.0'
    spec.homepage                 = 'https://github.com/Licryle/HSKFlashcardsWidget'
    spec.source                   = { :http=> ''}
    spec.authors                  = ''
    spec.license                  = ''
    spec.summary                  = 'Google Drive Backup'
    spec.vendored_frameworks      = 'build/cocoapods/framework/googledrivebackup.framework'
    spec.libraries                = 'c++'
    spec.dependency 'GoogleAPIClientForREST/Drive'
    spec.dependency 'GoogleSignIn'
    if !Dir.exist?('build/cocoapods/framework/googledrivebackup.framework') || Dir.empty?('build/cocoapods/framework/googledrivebackup.framework')
        raise "
        Kotlin framework 'googledrivebackup' doesn't exist yet, so a proper Xcode project can't be generated.
        'pod install' should be executed after running ':generateDummyFramework' Gradle task:
            ./gradlew :googledrivebackup:generateDummyFramework
        Alternatively, proper pod installation is performed during Gradle sync in the IDE (if Podfile location is set)"
    end
    spec.xcconfig = {
        'ENABLE_USER_SCRIPT_SANDBOXING' => 'NO',
    }
    spec.pod_target_xcconfig = {
        'KOTLIN_PROJECT_PATH' => ':googledrivebackup',
        'PRODUCT_MODULE_NAME' => 'googledrivebackup',
    }
    spec.script_phases = [
        {
            :name => 'Build googledrivebackup',
            :execution_position => :before_compile,
            :shell_path => '/bin/sh',
            :script => <<-SCRIPT
                if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
                    echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
                    exit 0
                fi
                set -ev
                REPO_ROOT="$PODS_TARGET_SRCROOT"
                "$REPO_ROOT/../gradlew" -p "$REPO_ROOT" $KOTLIN_PROJECT_PATH:syncFramework \
                    -Pkotlin.native.cocoapods.platform=$PLATFORM_NAME \
                    -Pkotlin.native.cocoapods.archs="$ARCHS" \
                    -Pkotlin.native.cocoapods.configuration="$CONFIGURATION"
            SCRIPT
        }
    ]
end
