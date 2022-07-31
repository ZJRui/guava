package sachin.guava.learn.eventbus;

import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;

import java.util.concurrent.Executors;

public class Demo {

    public static void main(String[] args)  throws Exception{
        test();
    }

    public static void test() throws Exception{
        EventBus eventBus=new AsyncEventBus(Executors.newCachedThreadPool());
        eventBus.register(new EventListener());
        eventBus.post("3");
    }
}
