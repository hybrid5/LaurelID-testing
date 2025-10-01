FROM ghcr.io/cirruslabs/android-sdk:35
USER root
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" "system-images;android-35;google_apis;x86_64"
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
WORKDIR /workspace
