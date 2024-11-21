[![Release](https://jitpack.io/v/umjammer/vavi-sound-nsf.svg)](https://jitpack.io/#umjammer/vavi-sound-nsf)
[![Java CI](https://github.com/umjammer/vavi-sound-nsf/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-sound-nsf/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-sound-nsf/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/umjammer/vavi-sound-nsf/actions/workflows/codeql-analysis.yml)
![Java](https://img.shields.io/badge/Java-17-b07219)

# vavi-sound-nsf

<img src="https://github.com/umjammer/vavi-sound-nsf/assets/493908/f9af5c46-ad4b-4e9b-801c-8ac87cf2b669" width=160 alt="nes logo"/><sub><a href="https://www.nintendo.com/">© Nintendo</a></sub>

[NSF](https://www.nesdev.org/wiki/NSF) Java Sound SPI powered by [nsf](https://github.com/orangelando/nsf)

## Install

 * [maven](https://jitpack.io/#umjammer/vavi-sound-nsf)

## Usage

```java
AudioInputStream ais = AudioSystem.getAudioInputStream(Paths.get(nsf).toFile());
Clip clip = AudioSystem.getClip();
clip.open(AudioSystem.getAudioInputStream(new AudioFormat(Encoding.PCM_SIGNED, 44100, 16, 1, 2, 44100, false, props), ais));
clip.loop(Clip.LOOP_CONTINUOUSLY);
```

### properties for target `AudioFormat`

* `track` ... specify track # in the file to play
* `maxPlaySecs` ... specify max play time in \[sec]

## References

* [nsf](https://github.com/orangelando/nsf)
* [Festalon](https://github.com/ahefner/festalon)

## TODO

 * ~~improve decoding speed (i7 imac)~~
   * on m2 ultra mac no problem
 * ~~use jpl instead of jul~~
