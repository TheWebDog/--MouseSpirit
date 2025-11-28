mkdir -p app/src/main/cpp
cp -r moonlight-android/app/src/main/jni/moonlight-core/* app/src/main/cpp/
cp -r moonlight-android/app/src/main/java/com/limelight/nvstream/jni app/src/main/java/com/limelight/nvstream/
cp -r moonlight-android/app/src/main/java/com/limelight/nvstream/av app/src/main/java/com/limelight/nvstream/
cp moonlight-android/app/src/main/java/com/limelight/nvstream/NvConnectionListener.java app/src/main/java/com/limelight/nvstream/
