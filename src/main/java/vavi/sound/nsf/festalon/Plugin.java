/*
 * Copyright (c) 2006 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.nsf.festalon;


/**
 * Plugin.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060501 nsano initial version <br>
 */
abstract class Plugin {
    public int videoSystem;
    public String copyright;
    public String artist;
    public String gameName;
    public int startingSong;
    public int totalSongs;
    public int totalChannels;
    public String[] songNames;
    public int[] songLengths;
    public int[] songFades;
    public String ripper;
    public int outChannels;

    //----

    /** */
    public abstract float[] emulate(int[] count);
    /** */
    public abstract void disable(int t);
}

/* */
