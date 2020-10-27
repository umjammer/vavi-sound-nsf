/*
 * Copyright (c) 2020 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.nsf;


import javax.sound.sampled.AudioFormat;


/**
 * Encodings used by the NSF audio decoder.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 201027 nsano initial version <br>
 */
public class NsfEncoding extends AudioFormat.Encoding {

    /** Specifies any ALAC encoded data. */
    public static final NsfEncoding NSF = new NsfEncoding("NSF");

    /**
     * Constructs a new encoding.
     *
     * @param name Name of the NSF encoding.
     */
    public NsfEncoding(String name) {
        super(name);
    }
}

/* */
