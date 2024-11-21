/*
 * Copyright (c) 2011 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import vavi.sound.fidlib.FidFilter;


/**
 * FidlibTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2011/02/18 umjammer initial version <br>
 */
public class FidlibTest {

    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        System.err.println(FidFilter.fid_version());
        FidFilter filter = FidFilter.Factory.newInstance();
        filter.fid_list_filters(System.err);
    }
}
