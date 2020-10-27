/*
 * https://github.com/orangelando/nsf
 */

package vavi.sound.nsf.nsf;

import java.io.IOException;
import java.util.Objects;


final class APUSamplePipe {

    final APUSampleSupplier sampleSupplier;
    final APUSampleConsumer sampleConsumer;

    APUSamplePipe(APUSampleSupplier sampleSupplier, APUSampleConsumer sampleConsumer) {
        this.sampleSupplier = Objects.requireNonNull(sampleSupplier);
        this.sampleConsumer = Objects.requireNonNull(sampleConsumer);
    }

    void sample(float scale) throws IOException {
        sampleConsumer.consume(scale * sampleSupplier.sample());
    }
}
