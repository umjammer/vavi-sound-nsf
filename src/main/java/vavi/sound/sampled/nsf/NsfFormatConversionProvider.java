/*
 * Copyright (c) 2020 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.nsf;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.spi.FormatConversionProvider;

import static javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED;
import static javax.sound.sampled.AudioSystem.NOT_SPECIFIED;


/**
 * NsfFormatConversionProvider.
 * <p>
 * system property
 * {@code vavi.sound.sampled.nsf.festalon} ... use festalon engine or not. default {@code false}
 * </p>
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 201027 nsano initial version <br>
 */
public class NsfFormatConversionProvider extends FormatConversionProvider {

    private static final Logger logger = System.getLogger(NsfFormatConversionProvider.class.getName());

    private static boolean festalon = false;

    static {
        festalon = System.getProperty("vavi.sound.sampled.nsf.festalon", "false").equals("true");
logger.log(Level.TRACE, "vavi.sound.sampled.nsf.festalon: " + festalon);
    }

    @Override
    public AudioFormat.Encoding[] getSourceEncodings() {
        return new AudioFormat.Encoding[] { NsfEncoding.NSF };
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings() {
        return new AudioFormat.Encoding[] { PCM_SIGNED };
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings(AudioFormat sourceFormat) {
        if (sourceFormat.getEncoding() instanceof NsfEncoding) {
            return new AudioFormat.Encoding[] { PCM_SIGNED };
        } else {
            return new AudioFormat.Encoding[0];
        }
    }

    @Override
    public AudioFormat[] getTargetFormats(AudioFormat.Encoding targetEncoding, AudioFormat sourceFormat) {
        if (sourceFormat.getEncoding() instanceof NsfEncoding && targetEncoding.equals(PCM_SIGNED)) {
            return new AudioFormat[] {
                new AudioFormat(sourceFormat.getSampleRate(),
                                16,             // sample size in bits
                                sourceFormat.getChannels(),
                                true,                  // signed
                                false)                        // little endian (for PCM wav)
            };
        } else {
            return new AudioFormat[0];
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(AudioFormat.Encoding targetEncoding, AudioInputStream sourceStream) {
        try {
            if (isConversionSupported(targetEncoding, sourceStream.getFormat())) {
                AudioFormat[] formats = getTargetFormats(targetEncoding, sourceStream.getFormat());
                if (formats != null && formats.length > 0) {
                    AudioFormat sourceFormat = sourceStream.getFormat();
                    AudioFormat targetFormat = formats[0];
                    if (sourceFormat.equals(targetFormat)) {
                        return sourceStream;
                    } else if (sourceFormat.getEncoding() instanceof NsfEncoding && targetFormat.getEncoding().equals(PCM_SIGNED)) {
                        if (festalon)
                            return new Festalon2PcmAudioInputStream(sourceStream, targetFormat, NOT_SPECIFIED, targetFormat.properties());
                        else
                            return new Nsf2PcmAudioInputStream(sourceStream, targetFormat, NOT_SPECIFIED, targetFormat.properties());
                    } else if (sourceFormat.getEncoding().equals(PCM_SIGNED) && targetFormat.getEncoding() instanceof NsfEncoding) {
                        throw new IllegalArgumentException("unable to convert " + sourceFormat + " to " + targetFormat);
                    } else {
                        throw new IllegalArgumentException("unable to convert " + sourceFormat + " to " + targetFormat.toString());
                    }
                } else {
                    throw new IllegalArgumentException("target format not found");
                }
            } else {
                throw new IllegalArgumentException("conversion not supported");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(AudioFormat targetFormat, AudioInputStream sourceStream) {
        try {
            if (isConversionSupported(targetFormat, sourceStream.getFormat())) {
                AudioFormat[] formats = getTargetFormats(targetFormat.getEncoding(), sourceStream.getFormat());
                if (formats != null && formats.length > 0) {
                    AudioFormat sourceFormat = sourceStream.getFormat();
                    if (sourceFormat.equals(targetFormat)) {
                        return sourceStream;
                    } else if (sourceFormat.getEncoding() instanceof NsfEncoding &&
                               targetFormat.getEncoding().equals(PCM_SIGNED)) {
                        if (festalon)
                            return new Festalon2PcmAudioInputStream(sourceStream, targetFormat, NOT_SPECIFIED, targetFormat.properties());
                        else
                            return new Nsf2PcmAudioInputStream(sourceStream, targetFormat, NOT_SPECIFIED, targetFormat.properties());
                    } else if (sourceFormat.getEncoding().equals(PCM_SIGNED) && targetFormat.getEncoding() instanceof NsfEncoding) {
                        throw new IllegalArgumentException("unable to convert " + sourceFormat + " to " + targetFormat);
                    } else {
                        throw new IllegalArgumentException("unable to convert " + sourceFormat + " to " + targetFormat);
                    }
                } else {
                    throw new IllegalArgumentException("target format not found");
                }
            } else {
                throw new IllegalArgumentException("conversion not supported");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
