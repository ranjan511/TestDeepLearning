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

package org.deeplearning4j.clustering.vptree;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.deeplearning4j.clustering.BaseDL4JTest;
import org.deeplearning4j.clustering.sptree.DataPoint;
import org.joda.time.Duration;
import org.junit.BeforeClass;
import org.junit.Test;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.primitives.Counter;
import org.nd4j.linalg.primitives.Pair;

import java.util.*;

import static org.junit.Assert.*;

/**
 * @author Anatoly Borisov
 */
@Slf4j
public class VpTreeNodeTest extends BaseDL4JTest {


    private static class DistIndex implements Comparable<DistIndex> {
        public double dist;
        public int index;

        public int compareTo(DistIndex r) {
            return Double.compare(dist, r.dist);
        }
    }

    @BeforeClass
    public static void beforeClass(){
        Nd4j.setDataType(DataType.FLOAT);
    }

    @Test
    public void testKnnK() {
        INDArray arr = Nd4j.randn(10, 5);
        VPTree t = new VPTree(arr, false);
        List<DataPoint> resultList = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        t.search(arr.getRow(0), 5, resultList, distances);
        assertEquals(5, resultList.size());
    }


    @Test
    public void testParallel_1() {
        int k = 5;

        for (int e = 0; e < 5; e++) {
            Nd4j.getRandom().setSeed(7);
            INDArray randn = Nd4j.rand(100, 3);
            VPTree vpTree = new VPTree(randn, false, 4);
            Nd4j.getRandom().setSeed(7);
            VPTree vpTreeNoParallel = new VPTree(randn, false, 1);
            List<DataPoint> results = new ArrayList<>();
            List<Double> distances = new ArrayList<>();
            List<DataPoint> noParallelResults = new ArrayList<>();
            List<Double> noDistances = new ArrayList<>();
            vpTree.search(randn.getRow(0), k, results, distances, true);
            vpTreeNoParallel.search(randn.getRow(0), k, noParallelResults, noDistances, true);

            assertEquals("Failed at iteration " + e, k, results.size());
            assertEquals("Failed at iteration " + e, noParallelResults.size(), results.size());
            assertNotEquals(randn.getRow(0, true), results.get(0).getPoint());
            assertEquals("Failed at iteration " + e, noParallelResults, results);
            assertEquals("Failed at iteration " + e, noDistances, distances);
        }
    }

    @Test
    public void testParallel_2() {
        int k = 5;

        for (int e = 0; e < 5; e++) {
            Nd4j.getRandom().setSeed(7);
            INDArray randn = Nd4j.rand(100, 3);
            VPTree vpTree = new VPTree(randn, false, 4);
            Nd4j.getRandom().setSeed(7);
            VPTree vpTreeNoParallel = new VPTree(randn, false, 1);
            List<DataPoint> results = new ArrayList<>();
            List<Double> distances = new ArrayList<>();
            List<DataPoint> noParallelResults = new ArrayList<>();
            List<Double> noDistances = new ArrayList<>();
            vpTree.search(randn.getRow(0), k, results, distances, false);
            vpTreeNoParallel.search(randn.getRow(0), k, noParallelResults, noDistances, false);

            assertEquals("Failed at iteration " + e, k, results.size());
            assertEquals("Failed at iteration " + e, noParallelResults.size(), results.size());
            assertEquals(randn.getRow(0, true), results.get(0).getPoint());
            assertEquals("Failed at iteration " + e, noParallelResults, results);
            assertEquals("Failed at iteration " + e, noDistances, distances);
        }
    }

    @Test
    public void testReproducibility() {
        val results = new ArrayList<DataPoint>();
        val distances = new ArrayList<Double>();
        Nd4j.getRandom().setSeed(7);
        val randn = Nd4j.rand(1000, 100);

        for (int e = 0; e < 10; e++) {
            Nd4j.getRandom().setSeed(7);
            val vpTree = new VPTree(randn, false, 1);

            val cresults = new ArrayList<DataPoint>();
            val cdistances = new ArrayList<Double>();
            vpTree.search(randn.getRow(0), 5, cresults, cdistances);

            if (e == 0) {
                results.addAll(cresults);
                distances.addAll(cdistances);
            } else {
                assertEquals("Failed at iteration " + e, results, cresults);
                assertEquals("Failed at iteration " + e, distances, cdistances);
            }
        }
    }

    @Test
    public void knnManualRandom() {
        knnManual(Nd4j.randn(3, 5));
    }

    @Test
    public void knnManualNaturals() {
        knnManual(generateNaturalsMatrix(20, 2));
    }

    public static void knnManual(INDArray arr) {
        Nd4j.getRandom().setSeed(7);
        VPTree t = new VPTree(arr, false);
        int k = 1;
        int m = arr.rows();
        for (int targetIndex = 0; targetIndex < m; targetIndex++) {
            // Do an exhaustive search
            TreeSet<Integer> s = new TreeSet<>();
            INDArray query = arr.getRow(targetIndex, true);

            Counter<Integer> counter = new Counter<>();
            for (int j = 0; j < m; j++) {
                double d = t.distance(query, (arr.getRow(j, true)));
                counter.setCount(j, (float) d);

            }

            PriorityQueue<Pair<Integer, Double>> pq = counter.asReversedPriorityQueue();
            // keep closest k
            for (int i = 0; i < k; i++) {
                Pair<Integer, Double> di = pq.poll();
                System.out.println("exhaustive d=" + di.getFirst());
                s.add(di.getFirst());
            }

            // Check what VPTree gives for results
            List<DataPoint> results = new ArrayList<>();
            VPTreeFillSearch fillSearch = new VPTreeFillSearch(t, k, query);
            fillSearch.search();
            results = fillSearch.getResults();

            //List<DataPoint> items = t.getItems();
            TreeSet<Integer> resultSet = new TreeSet<>();

            // keep k in a set
            for (int i = 0; i < k; ++i) {
                DataPoint result = results.get(i);
                int r = result.getIndex();
                resultSet.add(r);
            }



            // check
            for (int r : resultSet) {
                INDArray expectedResult = arr.getRow(r, true);
                if (!s.contains(r)) {
                    fillSearch = new VPTreeFillSearch(t, k, query);
                    fillSearch.search();
                    results = fillSearch.getResults();
                }
                assertTrue(String.format(
                                "VPTree result" + " %d is not in the " + "closest %d " + " " + "from the exhaustive"
                                                + " search with query point %s and "
                                                + "result %s and target not found %s",
                                r, k, query.toString(), results.toString(), expectedResult.toString()), s.contains(r));
            }

        }
    }

    @Test
    public void vpTreeTest() {
        List<DataPoint> points = new ArrayList<>();
        points.add(new DataPoint(0, Nd4j.create(new double[] {55, 55})));
        points.add(new DataPoint(1, Nd4j.create(new double[] {60, 60})));
        points.add(new DataPoint(2, Nd4j.create(new double[] {65, 65})));
        VPTree tree = new VPTree(points, "euclidean");
        List<DataPoint> add = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        tree.search(Nd4j.create(new double[] {50, 50}), 1, add, distances);
        DataPoint assertion = add.get(0);
        assertEquals(new DataPoint(0, Nd4j.create(new double[] {55, 55}).reshape(1,2)), assertion);

        tree.search(Nd4j.create(new double[] {61, 61}), 2, add, distances, false);
        assertion = add.get(0);
        assertEquals(Nd4j.create(new double[] {60, 60}).reshape(1,2), assertion.getPoint());
    }

    @Test(expected = ND4JIllegalStateException.class)
    public void vpTreeTest2() {
        List<DataPoint> points = new ArrayList<>();
        points.add(new DataPoint(0, Nd4j.create(new double[] {55, 55})));
        points.add(new DataPoint(1, Nd4j.create(new double[] {60, 60})));
        points.add(new DataPoint(2, Nd4j.create(new double[] {65, 65})));
        VPTree tree = new VPTree(points, "euclidean");

        tree.search(Nd4j.create(1, 10), 2, new ArrayList<DataPoint>(), new ArrayList<Double>());
    }

    @Test(expected = ND4JIllegalStateException.class)
    public void vpTreeTest3() {
        List<DataPoint> points = new ArrayList<>();
        points.add(new DataPoint(0, Nd4j.create(new double[] {55, 55})));
        points.add(new DataPoint(1, Nd4j.create(new double[] {60, 60})));
        points.add(new DataPoint(2, Nd4j.create(new double[] {65, 65})));
        VPTree tree = new VPTree(points, "euclidean");

        tree.search(Nd4j.create(2, 10), 2, new ArrayList<DataPoint>(), new ArrayList<Double>());
    }

    @Test(expected = ND4JIllegalStateException.class)
    public void vpTreeTest4() {
        List<DataPoint> points = new ArrayList<>();
        points.add(new DataPoint(0, Nd4j.create(new double[] {55, 55})));
        points.add(new DataPoint(1, Nd4j.create(new double[] {60, 60})));
        points.add(new DataPoint(2, Nd4j.create(new double[] {65, 65})));
        VPTree tree = new VPTree(points, "euclidean");

        tree.search(Nd4j.create(2, 10, 10), 2, new ArrayList<DataPoint>(), new ArrayList<Double>());
    }

    public static INDArray generateNaturalsMatrix(int nrows, int ncols) {
        INDArray col = Nd4j.arange(0, nrows).reshape(nrows, 1).castTo(DataType.DOUBLE);
        INDArray points = Nd4j.create(DataType.DOUBLE, nrows, ncols);
        if (points.isColumnVectorOrScalar())
            points = col.dup();
        else {
            for (int i = 0; i < ncols; i++)
                points.putColumn(i, col);
        }
        return points;
    }

    @Test
    public void testVPSearchOverNaturals1D() throws Exception {
        testVPSearchOverNaturalsPD(20, 1, 5);
    }

    @Test
    public void testVPSearchOverNaturals2D() throws Exception {
        testVPSearchOverNaturalsPD(20, 2, 5);
    }

    @Test
    public void testTreeOrder() {

        int N = 10, dim = 1;
        INDArray dataset = Nd4j.randn(N, dim);
        double[] rawData = dataset.toDoubleVector();
        Arrays.sort(dataset.toDoubleVector());
        dataset = Nd4j.createFromArray(rawData).reshape(1,N);

        List<DataPoint> points = new ArrayList<>();

        for (int i = 0; i < rawData.length; ++i) {
            points.add(new DataPoint(i, Nd4j.create(new double[]{rawData[i]})));
        }

        VPTree tree = new VPTree(points, "euclidean");
        INDArray points1 = tree.getItems();
        assertEquals(dataset, points1);
    }

    @Test
    public void testNearestNeighbors() {

        List<DataPoint> points = new ArrayList<>();

        points.add(new DataPoint(0, Nd4j.create(new double[] {0.83494041,  1.70294823, -1.34172191,  0.02350972,
                                                                    -0.87519361,  0.64401935, -0.5634212,  -1.1274308,
                                                                    0.19245948, -0.11349026})));
        points.add(new DataPoint(1, Nd4j.create(new double[] {-0.41115537, -0.7686138,  -0.67923172, 1.01638281,
                                                                    0.04390801,  0.29753166,  0.78915771, -0.13564866,
                                                                    -1.06053692, -0.15953041})));

        VPTree tree = new VPTree(points, "euclidean");

        List<DataPoint> results = new ArrayList<>();
        List<Double> distances = new ArrayList<>();

        final int k = 1;
        double[] input = new double[]{0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5};
        tree.search(Nd4j.createFromArray(input), k, results, distances);
        assertEquals(k, distances.size());
        assertEquals(2.7755637844503016, distances.get(0), 1e-5);

        double[] results_pattern = new double[]{-0.41115537, -0.7686138 , -0.67923172,  1.01638281,  0.04390801,
                0.29753166,  0.78915771, -0.13564866, -1.06053692, -0.15953041};
        for (int i = 0; i < results_pattern.length; ++i) {
            assertEquals(results_pattern[i], results.get(0).getPoint().getDouble(i), 1e-5);
        }
    }

    @Test
    public void performanceTest() {
        final int dim = 300;
        final int rows = 8000;
        final int k = 5;

        INDArray inputArrray = Nd4j.linspace(DataType.DOUBLE, 0.0, 1.0, rows * dim).reshape(rows, dim);

        //INDArray inputArrray = Nd4j.randn(DataType.DOUBLE, 200000, dim);
        long start = System.currentTimeMillis();
        VPTree tree = new VPTree(inputArrray, "euclidean");
        long end = System.currentTimeMillis();
        Duration duration = new Duration(start, end);
        System.out.println("Elapsed time for tree construction " + duration.getStandardSeconds());

        double[] input = new double[dim];
        for (int i = 0; i < dim; ++i) {
            input[i] = 119;
        }
        List<DataPoint> results = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        start = System.currentTimeMillis();
        tree.search(Nd4j.createFromArray(input), k, results, distances);
        end = System.currentTimeMillis();
        duration = new Duration(start, end);
        System.out.println("Elapsed time for tree search " + duration.getStandardSeconds());
        assertEquals(1590.2987519949422, distances.get(0), 1e-4);
    }

    public static void testVPSearchOverNaturalsPD(int nrows, int ncols, int K) throws Exception {
        final int queryPoint = 12;

        INDArray points = generateNaturalsMatrix(nrows, ncols);
        INDArray query = Nd4j.zeros(DataType.DOUBLE, 1, ncols);
        for (int i = 0; i < ncols; i++)
            query.putScalar(0, i, queryPoint);

        INDArray trueResults = Nd4j.zeros(DataType.DOUBLE, K, ncols);
        for (int j = 0; j < K; j++) {
            int pt = queryPoint - K / 2 + j;
            for (int i = 0; i < ncols; i++)
                trueResults.putScalar(j, i, pt);
        }

        VPTree tree = new VPTree(points, "euclidean", 1, false);

        List<DataPoint> results = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        tree.search(query, K, results, distances, false);
        int dimensionToSort = 0;

        INDArray sortedResults = Nd4j.zeros(DataType.DOUBLE, K, ncols);
        int i = 0;
        for (DataPoint p : results) {
            sortedResults.putRow(i++, p.getPoint());
        }

        sortedResults = Nd4j.sort(sortedResults, dimensionToSort, true);
        assertTrue(trueResults.equalsWithEps(sortedResults, 1e-5));

        VPTreeFillSearch fillSearch = new VPTreeFillSearch(tree, K, query);
        fillSearch.search();
        results = fillSearch.getResults();
        sortedResults = Nd4j.zeros(DataType.FLOAT, K, ncols);
        i = 0;
        for (DataPoint p : results)
            sortedResults.putRow(i++, p.getPoint());
        INDArray[] sortedWithIndices = Nd4j.sortWithIndices(sortedResults, dimensionToSort, true);;
        sortedResults = sortedWithIndices[1];
        assertEquals(trueResults.sumNumber().doubleValue(), sortedResults.sumNumber().doubleValue(), 1e-5);
    }

}
