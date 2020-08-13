package org.nd4j.linalg.api.ops.impl.layers.recurrent.outputs;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.base.Preconditions;
import org.nd4j.linalg.api.ops.impl.layers.recurrent.GRUCell;

/**
 * The outputs of a GRU cell ({@link GRUCell}.
 */
@Getter
public class GRUCellOutputs {

    /**
     * Reset gate output [batchSize, numUnits].
     */
    private SDVariable r;

    /**
     * Update gate output [batchSize, numUnits].
     */
    private SDVariable u;

    /**
     * Cell gate output [batchSize, numUnits].
     */
    private SDVariable c;

    /**
     * Current cell output [batchSize, numUnits].
     */
    private SDVariable h;

    public GRUCellOutputs(SDVariable[] outputs){
        Preconditions.checkArgument(outputs.length == 4,
                "Must have 4 GRU cell outputs, got %s", outputs.length);

        r = outputs[0];
        u = outputs[1];
        c = outputs[2];
        h = outputs[3];
    }

    /**
     * Get all outputs returned by the cell.
     */
    public List<SDVariable> getAllOutputs(){
        return Arrays.asList(r, u, c, h);
    }

    /**
     * Get h, the output of the cell.
     *
     * Has shape [batchSize, numUnits].
     */
    public SDVariable getOutput(){
        return h;
    }

}
