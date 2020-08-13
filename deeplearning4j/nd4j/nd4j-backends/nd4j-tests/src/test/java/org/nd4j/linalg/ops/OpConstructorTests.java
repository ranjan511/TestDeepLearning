package org.nd4j.linalg.ops;

import org.junit.Ignore;
import org.junit.Test;
import org.nd4j.autodiff.functions.DifferentialFunction;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.linalg.BaseNd4jTest;
import org.nd4j.linalg.api.ops.NoOp;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.linalg.lossfunctions.ILossFunction;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.junit.Assert.assertEquals;

@Ignore //AB 2019/08/23 Ignored for now
public class OpConstructorTests extends BaseNd4jTest {

    public OpConstructorTests(Nd4jBackend backend) {
        super(backend);
    }

    //Ignore individual classes
    protected Set<Class<?>> exclude = new HashSet<>(
            Arrays.asList(
                    NoOp.class
            )
    );

    //Ignore whole sets of classes based on regex
    protected String[] ignoreRegexes = new String[]{
            "org\\.nd4j\\.linalg\\.api\\.ops\\.impl\\.controlflow\\..*"
    };

    @Test
    public void checkForINDArrayConstructors() throws Exception {
        /*
        Check that all op classes have at least one INDArray or INDArray[] constructor, so they can actually
        be used outside of SameDiff
         */

        Reflections f = new Reflections(new ConfigurationBuilder()
                .filterInputsBy(new FilterBuilder().include(FilterBuilder.prefix("org.nd4j.*")).exclude("^(?!.*\\.class$).*$"))
                .setUrls(ClasspathHelper.forPackage("org.nd4j")).setScanners(new SubTypesScanner()));

        Set<Class<? extends DifferentialFunction>> classSet = f.getSubTypesOf(DifferentialFunction.class);

        int count = 0;
        List<Class<?>> classes = new ArrayList<>();
        for(Class<?> c : classSet){
            if(Modifier.isAbstract(c.getModifiers()) || Modifier.isInterface(c.getModifiers()) || c == SDVariable.class || ILossFunction.class.isAssignableFrom(c))
                continue;

            if(exclude.contains(c))
                continue;

            String cn = c.getName();
            boolean ignored = false;
            for(String s : ignoreRegexes ){
                if(cn.matches(s)){
                    ignored = true;
                    break;
                }
            }
            if(ignored)
                continue;

            Constructor<?>[] constructors = c.getConstructors();
            boolean foundINDArray = false;
            for( int i=0; i<constructors.length; i++ ){
                Constructor<?> co = constructors[i];
                String str = co.toGenericString();      //This is a convenience hack for checking - returns strings like "public org.nd4j.linalg.api.ops.impl.reduce.floating.Norm2(org.nd4j.linalg.api.ndarray.INDArray,int...)"
                if(str.contains("INDArray") && !str.contains("SameDiff")){
                    foundINDArray = true;
                    break;
                }
            }

            if(!foundINDArray){
                classes.add(c);
            }
        }

        if(!classes.isEmpty()){
            Collections.sort(classes, new Comparator<Class<?>>() {
                @Override
                public int compare(Class<?> o1, Class<?> o2) {
                    return o1.getName().compareTo(o2.getName());
                }
            });
            for(Class<?> c : classes){
                System.out.println("No INDArray constructor: " + c.getName());
            }
        }
        assertEquals("Found " + classes.size() + " (non-ignored) op classes with no INDArray/INDArray[] constructors", 0, classes.size());

    }

    @Override
    public char ordering(){
        return 'c';
    }

}
