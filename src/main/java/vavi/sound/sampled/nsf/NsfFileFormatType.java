/*
 * Copyright (c) 2020 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.nsf;

import javax.sound.sampled.AudioFileFormat;


/**
 * FileFormatTypes used by the NSF audio decoder.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 201027 nsano initial version <br>
 */
public class NsfFileFormatType extends AudioFileFormat.Type {

    /**
     * Specifies an NSF file.
     */
    public static final AudioFileFormat.Type NSF = new NsfFileFormatType("NSF", "nsf");

    /**
     * Constructs a file type.
     *
     * @param name the name of the NSF File Format.
     * @param extension the file extension for this NSF File Format.
     */
    public NsfFileFormatType(String name, String extension) {
        super(name, extension);
    }
}
