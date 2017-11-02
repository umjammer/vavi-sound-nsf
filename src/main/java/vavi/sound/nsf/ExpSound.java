/*
 * Copyright (c) 2006 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.nsf;


/**
 * ExpSound.
 * 
 * @author <a href="mailto:vavivavi@yahoo.co.jp">Naohide Sano</a> (nsano)
 * @version 0.00 060501 nsano initial version <br>
 */
public abstract class ExpSound {
    /** */
    public abstract void fillHi();
    /** */
    public abstract void syncHi(int ts);
    /** */
    public abstract void kill();
    /** */
    public abstract void disable(int mask);
    /** */
    protected int channels;
}

/* */
