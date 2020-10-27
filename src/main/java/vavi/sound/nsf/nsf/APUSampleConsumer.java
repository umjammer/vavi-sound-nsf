/*
 * https://github.com/orangelando/nsf
 */

package vavi.sound.nsf.nsf;

import java.io.IOException;


interface APUSampleConsumer {

    void init() throws IOException ;

    void consume(float sample) throws IOException;

    void finish() throws IOException;
}
