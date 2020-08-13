/*******************************************************************************
 * Copyright (c) 2015-2018 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.deeplearning4j.nn.layers.samediff;

import lombok.val;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.api.TrainingConfig;
import org.deeplearning4j.nn.conf.layers.samediff.SDVertexParams;
import org.deeplearning4j.nn.conf.layers.samediff.SameDiffVertex;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.graph.vertex.BaseGraphVertex;
import org.deeplearning4j.nn.params.SameDiffParamInitializer;
import org.deeplearning4j.nn.workspace.ArrayType;
import org.deeplearning4j.nn.workspace.LayerWorkspaceMgr;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.base.Preconditions;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.layers.ExternalErrorsFunction;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.primitives.Pair;

import java.util.*;

/**
 * Implementation of a SameDiff graph vertex.
 * Note that users should not be extending this directly - instead, use {@link SameDiffVertex}
 *
 * @author Alex Black
 */
public class SameDiffGraphVertex extends BaseGraphVertex {

    protected SameDiffVertex config;
    protected SameDiff sameDiff;
    protected SDVariable outputVar;
    protected ExternalErrorsFunction fn;
    protected String outputKey;
    protected Map<String,SDVariable> inputVars;
    protected INDArray[] maskArrays;

    protected INDArray params;
    protected INDArray gradients;
    protected Map<String,INDArray> paramTable;
    protected Map<String,INDArray> gradTable;
    private MaskState currentMaskState;
    private int minibatchSize;

    public SameDiffGraphVertex(SameDiffVertex config, ComputationGraph graph, String name, int vertexIndex,
                                  INDArray paramsView, boolean initParams, DataType dataType) {
        super(graph, name, vertexIndex, null, null, dataType);
        this.config = config;
        SDVertexParams vp = config.getVertexParams();
        paramTable = SameDiffParamInitializer.getInstance().subsetAndReshape(vp.getParameterKeys(),
                vp.getParamShapes(), paramsView, null, config);
        if(initParams){
            config.initializeParameters(paramTable);
        }
        this.params = paramsView;
    }

    @Override
    public String toString() {
        return null;
    }

    @Override
    public boolean hasLayer() {
        return false;
    }

    @Override
    public Layer getLayer() {
        return null;
    }

    @Override
    public INDArray doForward(boolean training, LayerWorkspaceMgr workspaceMgr) {
        try(MemoryWorkspace ws = Nd4j.getWorkspaceManager().scopeOutOfWorkspaces()) {
            if(sameDiff == null){
                doInit();
            }

            Map<String,INDArray> phMap = new HashMap<>();
            config.validateInput(inputs);
            for(int i=0; i<inputs.length; i++ ){
                String name = config.getVertexParams().getInputs().get(i);
                final String maskName = name + "_mask";
                phMap.put(name, inputs[i]);
                if(maskArrays != null && maskArrays[i] != null) {
                    phMap.put(maskName, maskArrays[i]);
                }else{
                    phMap.put(maskName, createMask(dataType, inputs[i].shape()));
                }
            }

            if(paramTable != null && paramTable.size() > 0) {
                //Because DL4J parameters are views, and SameDiff uses DeviceLocal (which doesn't support views), we need to update the arrays on each iteration
                //TODO Find a more efficient solution for this
                for (Map.Entry<String, INDArray> e : paramTable.entrySet()) {
                    INDArray arr = e.getValue();
                    sameDiff.assignArray(arr, sameDiff.getVariable(e.getKey()));
                }
            }
            INDArray result = sameDiff.outputSingle(phMap, outputKey);

            //Clear placeholders and op inputs to ensure no out-of-scope arrays are still referenced anywhere
            sameDiff.clearPlaceholders(true);
            sameDiff.clearOpInputs();
            return workspaceMgr.dup(ArrayType.ACTIVATIONS, result);
        }
    }

    @Override
    public Pair<Gradient, INDArray[]> doBackward(boolean tbptt, LayerWorkspaceMgr workspaceMgr) {
        Gradient g = new DefaultGradient();

        INDArray[] dLdIns;
        try(MemoryWorkspace ws = Nd4j.getWorkspaceManager().scopeOutOfWorkspaces()){
            if(sameDiff == null){
                doInit();
            }

            List<String> inputNames = config.getVertexParams().getInputs();
            if(!sameDiff.hasGradientFunction()) {
                //Create when scoped out, to ensure any arrays are not in WS
                String[] inArr = inputNames.toArray(new String[inputNames.size()]);
                sameDiff.createGradFunction(inArr);
            }
            config.validateInput(inputs);
            Map<String,INDArray> phMap = new HashMap<>();
            List<String> inputs = config.getVertexParams().getInputs();
            int i=0;
            for(String s : inputs){
                phMap.put(s, this.inputs[i++]);
            }
            for( int j=0; j<this.inputs.length; j++ ){
                String name = inputs.get(j);
                final String maskName = name + "_mask";
                if(maskArrays != null && maskArrays[j] != null) {
                    phMap.put(maskName, maskArrays[j]);
                }else{
                    phMap.put(maskName, createMask(dataType, this.inputs[j].shape()));
                }
            }
            String epsName = fn.getGradPlaceholderName();
            phMap.put(epsName, epsilon);


            //Because DL4J parameters are views, and SameDiff uses DeviceLocal (which doesn't support views), we need to update the arrays on each iteration
            //TODO Find a more efficient solution for this
            for (Map.Entry<String, INDArray> e : paramTable.entrySet()) {
                INDArray arr = e.getValue();
                sameDiff.assignArray(arr, sameDiff.getVariable(e.getKey()));
            }

            List<String> required = new ArrayList<>(inputNames.size());     //Ensure that the input placeholder gradients are calculated
            for(String s : inputNames){
                required.add(sameDiff.getVariable(s).gradient().getVarName());
            }
            sameDiff.execBackwards(phMap, required);
            for(String s : paramTable.keySet() ){
                INDArray sdGrad = sameDiff.grad(s).getArr();
                INDArray dl4jGrad = gradTable.get(s);
                dl4jGrad.assign(sdGrad);                                            //TODO OPTIMIZE THIS
                g.gradientForVariable().put(s, dl4jGrad);
            }

            dLdIns = new INDArray[inputs.size()];
            String fnName = fn.getGradPlaceholderName();
            for(int j=0; j<inputs.size(); j++ ){
                String name = inputs.get(j);
                dLdIns[j] = sameDiff.grad(name).getArr();

                String gradName = sameDiff.grad(inputNames.get(j)).getVarName();
                if(dLdIns[j] == null && fnName.equals(gradName)){
                    //Edge case with lambda vertices like identity: SameDiff doesn't store the placeholders
                    // So, this getArr() can be trying to get placeholder from SameDiff instance, when it's available here
                    dLdIns[j] = epsilon;
                }
            }
        }

        //TODO optimize
        for( int i=0; i<dLdIns.length; i++ ){
            dLdIns[i] = workspaceMgr.dup(ArrayType.ACTIVATION_GRAD, dLdIns[i]);
        }

        //Clear placeholders and op inputs to ensure no out-of-scope arrays are still referenced anywhere
        sameDiff.clearPlaceholders(true);
        sameDiff.clearOpInputs();
        return new Pair<>(g, dLdIns);
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray backpropGradientsViewArray) {
        SDVertexParams vp = config.getVertexParams();
        gradTable = SameDiffParamInitializer.getInstance().subsetAndReshape(vp.getParameterKeys(),
                vp.getParamShapes(), backpropGradientsViewArray, null, config);
    }

    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArrays(INDArray[] maskArrays, MaskState currentMaskState, int minibatchSize) {
        this.maskArrays = maskArrays;
        this.currentMaskState = currentMaskState;

        return config.feedForwardMaskArrays(maskArrays, currentMaskState, minibatchSize);
    }


    protected void doInit(){
        try(MemoryWorkspace ws = Nd4j.getWorkspaceManager().scopeOutOfWorkspaces()) {
            sameDiff = SameDiff.create();

            inputVars = new LinkedHashMap<>();
            LinkedHashMap<String, SDVariable> maskVars = new LinkedHashMap<>();
            int i=0;
            for(String s : config.getVertexParams().getInputs()){
                val inputShape = inputs[i++].shape().clone();
                INDArray maskTemp = createMask(dataType, inputShape);
                inputShape[0] = -1;
                SDVariable inputVar = sameDiff.placeHolder(s, dataType, inputShape);
                inputVars.put(s, inputVar);
                long[] maskShape = maskTemp.shape().clone();
                maskShape[0] = -1;
                SDVariable maskVar = sameDiff.placeHolder(s + "_mask", maskTemp.dataType(), maskShape);
                maskVars.put(s, maskVar);
            }

            Map<String, long[]> paramShapes = config.getVertexParams().getParamShapes();
            Map<String, SDVariable> params = new LinkedHashMap<>();
            for (String s : paramShapes.keySet()) {
                val ps = paramShapes.get(s);
                SDVariable v = sameDiff.var(s, dataType, ps);
                params.put(s, v);
            }
            SDVariable layerOutput = config.defineVertex(sameDiff, inputVars, params, maskVars);
            Preconditions.checkNotNull(layerOutput, "Invalid output: layer output is null");
            outputVar = layerOutput;

            for (Map.Entry<String, INDArray> e : paramTable.entrySet()) {
                sameDiff.associateArrayWithVariable(e.getValue(), sameDiff.getVariable(e.getKey()));
            }

            //Define the function for external errors:
            fn = sameDiff.f().externalErrors(layerOutput);
            fn.outputVariable();

            this.outputKey = outputVar.getVarName();
        }
    }

    @Override
    public void clearVertex() {
        clear();
    }

    @Override
    public Map<String, INDArray> paramTable(boolean backpropOnly) {
        return paramTable;
    }

    @Override
    public TrainingConfig getConfig() {
        return config;
    }

    @Override
    public INDArray params() {
        return params;
    }

    @Override
    public INDArray getGradientsViewArray() {
        return gradients;
    }

    //Package private
    static INDArray createMask(DataType dataType, long[] shape){
        switch (shape.length){
            case 2: // FF-Type input
                return Nd4j.ones(dataType,shape[0], 1);
            case 3: // RNN-Type input
                return Nd4j.ones(dataType, shape[0], shape[2]);
            case 4: //CNN input
                return Nd4j.ones(dataType, shape[0], 1, 1, 1);
            default:
                Preconditions.throwEx("Can not create all-ones-mask for given input shape %s.", Arrays.toString(shape));
                return null;
        }
    }
}


