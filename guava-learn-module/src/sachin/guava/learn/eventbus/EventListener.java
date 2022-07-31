package sachin.guava.learn.eventbus;

import com.google.common.eventbus.Subscribe;

public class EventListener {

    @Subscribe
    public void listenInteger(Integer param){
        System.out.printf("listenInteger:" + param);
    }
    @Subscribe
    public void listeneString(String param){
        System.out.println("listeneString"+param);
    }

    @Subscribe
    public void listeneString2(String param){
        System.out.println("listeneString2"+param);
    }
}
