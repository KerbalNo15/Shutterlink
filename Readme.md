# Shutterlink
An open-source Android app to connect to Panasonic Lumix cameras. This project is not affiliated with Panasonic in any way.

## Why?
I created this application to make the wireless functions of older Panasonic cameras compatible with newer versions of Android. I got tired of moving my pictures with an SD card reader, and it felt like a shame to keep doing this when the camera has WiFi functionality. Shutterlink supports both viewing the camera contents and auto-downloading images as you take them.

## Does it Work With my Camera?
I don't know. I developed it for my G85 and GM1, so to the best of my knowledge those both work. I don't know how well other models will work; if you have a camera and are willing to test it, I would love to know what works and what doesn't.

## How do I use it?
I made a simple guide to setting up your camera [Here](getting_started.md)

## Known bugs?
Yes, many. This project is in alpha status, and it is not perfect.
- All portrait photos are displayed sideways in the thumbnail list. I have no idea how the official app fixes this since my camera doesn't seem to return orientation data with the thumbnail list.
- You cannot download videos. If you try, the app will immediately crash.
- Trying to download an image after your phone disconnects from the camera will cause a crash.
- Thumbnails are slow to load from some cameras, but this is probably a hardware limitation.
- Requesting an old page before the current one is finished loading will result in slow loads and the remaining results being appended to the older page of content.
- Changing the screen orientation will result in the UI being redrawn but the connection is still active. The app needs to be restarted if this happens.

For troubleshooting purposes, logcat will give you a ton of information. If you are running into connection issues, give that a try.

## May I help? What's the roadmap?
Yes! PRs, logcat history, however you want to help. This project is really just for me to use my camera, but if you get some value out of it you're welcome to give back. My goal is to make my camera functional again, I don't think this project will ever completely replace the Image App. The sky is the limit and your vision is the future.
