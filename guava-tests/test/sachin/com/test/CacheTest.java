package sachin.com.test;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import junit.framework.TestCase;

/**
 * @Author Sachin
 * @Date 2022/4/19
 **/
public class CacheTest extends TestCase {


    public void testNewBuilder() throws Exception {

        final ConstantLoader<Object, Integer> objectIntegerConstantLoader = new ConstantLoader<>(1);


        final LoadingCache<Object, Integer> cache = CacheBuilder.newBuilder().build(objectIntegerConstantLoader);

        final Integer integer = cache.get(2);


    }


    static final class ConstantLoader<K, V> extends CacheLoader<K, V> {
        private final V constant;


        ConstantLoader(V constant) {
            this.constant = constant;
        }

        @Override
        public V load(K key) throws Exception {
            return constant;
        }
    }

}
