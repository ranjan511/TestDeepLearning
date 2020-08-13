package org.nd4j.linalg.dataset.api.preprocessor;

import org.junit.Test;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.shape.LongShapeDescriptor;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;

import static org.junit.Assert.*;

public class CropAndResizeDataSetPreProcessorTest {

    @Test(expected = IllegalArgumentException.class)
    public void when_originalHeightIsZero_expect_IllegalArgumentException() {
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(0, 15, 5, 5, 4, 3, 3, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);
    }

    @Test(expected = IllegalArgumentException.class)
    public void when_originalWidthIsZero_expect_IllegalArgumentException() {
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(10, 0, 5, 5, 4, 3, 3, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);
    }

    @Test(expected = IllegalArgumentException.class)
    public void when_yStartIsNegative_expect_IllegalArgumentException() {
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(10, 15, -1, 5, 4, 3, 3, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);
    }

    @Test(expected = IllegalArgumentException.class)
    public void when_xStartIsNegative_expect_IllegalArgumentException() {
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(10, 15, 5, -1, 4, 3, 3, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);
    }

    @Test(expected = IllegalArgumentException.class)
    public void when_heightIsNotGreaterThanZero_expect_IllegalArgumentException() {
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(10, 15, 5, 5, 0, 3, 3, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);
    }

    @Test(expected = IllegalArgumentException.class)
    public void when_widthIsNotGreaterThanZero_expect_IllegalArgumentException() {
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(10, 15, 5, 5, 4, 0, 3, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);
    }

    @Test(expected = IllegalArgumentException.class)
    public void when_numChannelsIsNotGreaterThanZero_expect_IllegalArgumentException() {
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(10, 15, 5, 5, 4, 3, 0, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);
    }

    @Test(expected = NullPointerException.class)
    public void when_dataSetIsNull_expect_NullPointerException() {
        // Assemble
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(10, 15, 5, 5, 4, 3, 3, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);

        // Act
        sut.preProcess(null);
    }

    @Test
    public void when_dataSetIsEmpty_expect_emptyDataSet() {
        // Assemble
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(10, 15, 5, 5, 4, 3, 3, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);
        DataSet ds = new DataSet(null, null);

        // Act
        sut.preProcess(ds);

        // Assert
        assertTrue(ds.isEmpty());
    }

    @Test
    public void when_dataSetIs15wx10h_expect_3wx4hDataSet() {
        // Assemble
        int numChannels = 3;
        int height = 10;
        int width = 15;
        CropAndResizeDataSetPreProcessor sut = new CropAndResizeDataSetPreProcessor(height, width, 5, 5, 4, 3, 3, CropAndResizeDataSetPreProcessor.ResizeMethod.NearestNeighbor);
        INDArray input = Nd4j.create(LongShapeDescriptor.fromShape(new int[] { 1, height, width, numChannels }, DataType.FLOAT), true);
        for(int c = 0; c < numChannels; ++c) {
            for(int h = 0; h < height; ++h) {
                for(int w = 0; w < width; ++w) {
                    input.putScalar(0, h, w, c, c*100 + h*10 + w);
                }
            }
        }

        DataSet ds = new DataSet(input, null);

        // Act
        sut.preProcess(ds);

        // Assert
        INDArray results = ds.getFeatures();
        long[] shape = results.shape();
        assertArrayEquals(new long[]{1, 4, 3, 3}, shape);

        // Test a few values
        assertEquals(55.0, results.getDouble(0, 0, 0, 0), 0.0);
        assertEquals(155.0, results.getDouble(0, 0, 0, 1), 0.0);
        assertEquals(255.0, results.getDouble(0, 0, 0, 2), 0.0);

        assertEquals(56.0, results.getDouble(0, 0, 1, 0), 0.0);
        assertEquals(156.0, results.getDouble(0, 0, 1, 1), 0.0);
        assertEquals(256.0, results.getDouble(0, 0, 1, 2), 0.0);

        assertEquals(57.0, results.getDouble(0, 0, 2, 0), 0.0);
        assertEquals(157.0, results.getDouble(0, 0, 2, 1), 0.0);
        assertEquals(257.0, results.getDouble(0, 0, 2, 2), 0.0);

        assertEquals(65.0, results.getDouble(0, 1, 0, 0), 0.0);
        assertEquals(165.0, results.getDouble(0, 1, 0, 1), 0.0);
        assertEquals(265.0, results.getDouble(0, 1, 0, 2), 0.0);

        assertEquals(66.0, results.getDouble(0, 1, 1, 0), 0.0);
        assertEquals(166.0, results.getDouble(0, 1, 1, 1), 0.0);
        assertEquals(266.0, results.getDouble(0, 1, 1, 2), 0.0);

        assertEquals(75.0, results.getDouble(0, 2, 0, 0), 0.0);
        assertEquals(175.0, results.getDouble(0, 2, 0, 1), 0.0);
        assertEquals(275.0, results.getDouble(0, 2, 0, 2), 0.0);

        assertEquals(76.0, results.getDouble(0, 2, 1, 0), 0.0);
        assertEquals(176.0, results.getDouble(0, 2, 1, 1), 0.0);
        assertEquals(276.0, results.getDouble(0, 2, 1, 2), 0.0);
    }

}
