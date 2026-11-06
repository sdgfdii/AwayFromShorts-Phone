package com.afs.guard;
import org.json.*;
import java.util.*;
/** Read-only projections and calendar/preview behavior against the real model. */
public final class MemoryMomoModelTest {
    private static int checks;
    private static final long NOW=1793692800000L;
    private static final Set<String> ALL=Collections.emptySet();
    private static void ok(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    private static String deck(MemoryData m){return m.activeDecks().optJSONObject(0).optString("id");}
    private static String card(MemoryData m,String d,String text){JSONObject c=MemoryData.newCard(d,"qa",text,"answer","source","note");m.saveCard(c);return c.optString("id");}
    private static void log(MemoryData m,String d,String id,long when,int rating){JSONObject l=new JSONObject();MemoryData.put(l,"deck",d);MemoryData.put(l,"card",id);MemoryData.put(l,"ts",when);MemoryData.put(l,"rating",rating);m.db.optJSONArray("logs").put(l);}
    private static long date(int year,int month,int day,int hour){Calendar c=Calendar.getInstance();c.clear();c.set(year,month-1,day,hour,0);return c.getTimeInMillis();}
    public static void main(String[] args){TimeZone previous=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));preview();readonlyAndLoad();query();stats();calendar();large();}finally{TimeZone.setDefault(previous);}System.out.println("Memory Momo model: "+checks+" checks passed");}
    private static void preview(){
        for(int state=0;state<3;state++)for(int step=0;step<2;step++)for(int rating:new int[]{1,2,3}){
            MemoryData m=new MemoryData();String d=deck(m),id=card(m,d,"preview");JSONObject c=m.card(id);
            MemoryData.put(c,"state",state);MemoryData.put(c,"step",step);MemoryData.put(c,"reps",2);
            MemoryData.put(c,"stability",9);MemoryData.put(c,"difficulty",5);MemoryData.put(c,"last",NOW-3*MemoryScheduler.DAY);MemoryData.put(c,"due",NOW-1);
            MemoryData.put(m.deck(d),"requestRetention",0.95);MemoryData.put(m.deck(d),"maxInterval",30);
            MemoryFlashcards.configure(m,new JSONArray().put(d),20,10,NOW);
            String before=m.db.toString();MemoryScheduler.State predicted=m.previewRating(id,rating,NOW);
            ok(before.equals(m.db.toString()),"preview does not mutate any field");
            m.rateFlashcard(id,rating,NOW,MemoryData.date(NOW));MemoryScheduler.State actual=MemoryData.state(m.card(id));
            ok(predicted.due==actual.due&&predicted.state==actual.state&&predicted.reps==actual.reps&&predicted.lapses==actual.lapses&&predicted.step==actual.step&&predicted.last==actual.last&&predicted.stability==actual.stability&&predicted.difficulty==actual.difficulty,"preview equals real persisted rating");
        }
        MemoryData m=new MemoryData();String id=card(m,deck(m),"fresh");
        ok(m.previewRating(id,1,NOW).due==NOW+60000,"fresh forget minute step");
        ok(m.previewRating(id,3,NOW).due==NOW+600000,"fresh remember ten-minute step");
        boolean rejected=false;try{m.previewRating("absent",3,NOW);}catch(IllegalArgumentException e){rejected=true;}ok(rejected,"missing card rejected");
        rejected=false;try{m.previewRating(id,0,NOW);}catch(IllegalArgumentException e){rejected=true;}ok(rejected,"invalid rating rejected");
        m.deleteDeck(deck(m),NOW);rejected=false;try{m.previewRating(id,3,NOW);}catch(IllegalArgumentException e){rejected=true;}ok(rejected,"deleted card preview rejected");
    }
    private static void readonlyAndLoad(){
        MemoryData m=new MemoryData();String d=deck(m),fresh=card(m,d,"fresh"),old=card(m,d,"old");
        MemoryData.put(m.card(old),"reps",2);MemoryData.put(m.card(old),"due",NOW-1);
        String before=m.db.toString();MemoryFlashcards.Load load=MemoryFlashcards.inspectLoad(m,NOW);
        ok(!load.sessionExists&&load.queued==0&&load.backlog==1&&load.availableNew==1,"inspect absent session retains backlog and availability");
        MemoryCardQuery.page(m,MemoryCardQuery.Filter.TODAY,ALL,NOW,0,50);MemoryStats.periods(m,NOW,MemoryStats.Period.MONTH,12,ALL);MemoryStats.stabilityBands(m,ALL);
        ok(before.equals(m.db.toString()),"all derived accessors leave plans and queue absent");
        MemoryFlashcards.configure(m,new JSONArray().put(d),20,10,NOW);load=MemoryFlashcards.inspectLoad(m,NOW);
        ok(load.sessionExists&&load.queued==2&&load.newPending==1&&load.reviewPending==1,"new and review split existing session");
        String first=MemoryFlashcards.next(m,NOW).optString("id");m.rateFlashcard(first,1,NOW,MemoryData.date(NOW));load=MemoryFlashcards.inspectLoad(m,NOW);
        ok(load.processed==1&&load.newPending+load.reviewPending==2,"forgotten processed once and stays pending for relearning");
        load=MemoryFlashcards.inspectLoad(m,NOW+2*MemoryScheduler.DAY);ok(!load.sessionExists&&load.queued==0,"yesterday queue not today's queue");
    }
    private static void query(){
        MemoryData m=new MemoryData();String d=deck(m),a=card(m,d,"alpha"),b=card(m,d,"beta"),c=card(m,d,"gamma");
        MemoryData.put(m.card(a),"lapses",3);MemoryData.put(m.card(a),"due",20);
        MemoryData.put(m.card(b),"lapses",3);MemoryData.put(m.card(b),"due",10);MemoryData.put(m.card(c),"lapses",2);
        String before=m.db.toString();List<String> result=MemoryCardQuery.page(m,MemoryCardQuery.Filter.DIFFICULT,ALL,NOW,0,1);
        ok(result.size()==1&&result.get(0).equals(b),"difficult threshold and due tie-break");
        ok(MemoryCardQuery.page(m,MemoryCardQuery.Filter.DIFFICULT,ALL,NOW,1,50).get(0).equals(a),"second page no omission");
        ok(MemoryCardQuery.count(m,MemoryCardQuery.Filter.WRONG,ALL,NOW)==3,"wrong still includes any lapse");
        ok(MemoryCardQuery.count(m,MemoryCardQuery.Filter.ALL,ALL,NOW,"GAMMA")==1,"whole-library case-insensitive search");
        ok(MemoryCardQuery.page(m,MemoryCardQuery.Filter.ALL,ALL,NOW,Integer.MAX_VALUE,50).isEmpty(),"offset overflow safe");
        ok(before.equals(m.db.toString()),"queries do not write");m.deleteDeck(d,NOW);
        ok(MemoryCardQuery.count(m,MemoryCardQuery.Filter.ALL,ALL,NOW)==0,"trash subjects excluded");
    }
    private static void stats(){
        MemoryData m=new MemoryData();String d=deck(m),other=m.activeDecks().optJSONObject(1).optString("id"),id=card(m,d,"one"),id2=card(m,other,"two");
        log(m,d,id,NOW-1000,3);log(m,d,id,NOW-500,1);log(m,other,id2,NOW-1000,3);log(m,d,id,NOW+10000,3);
        MemoryStats.PeriodSeries series=MemoryStats.periods(m,NOW,MemoryStats.Period.DAY,30,Collections.singleton(d));
        ok(series.reviews[29]==2&&series.correct[29]==1&&series.uniqueCards[29]==1,"per-subject counts exclude future and distinguish attempts/cards");
        ok(MemoryStats.summary(m,NOW,Collections.singleton(d)).total==1,"summary filters subject");
        ok(MemoryStats.strength(m,Collections.singleton(d))[0]==1,"strength filters subject");
        MemoryData.put(m.card(id2),"reps",2);MemoryData.put(m.card(id2),"due",NOW-1);
        int allDue=MemoryStats.forecast(m,NOW,7,ALL)[0];
        ok(allDue==1&&MemoryStats.forecast(m,NOW,7,Collections.singleton(d))[0]==0,"forecast filters subject");
        ok(MemoryStats.dueByDeck(m,NOW,Collections.singleton(d)).isEmpty(),"due-by-deck filters subject");
        double[] values={9.99,10,30,60,90};for(double value:values){String x=card(m,d,"stable");JSONObject c=m.card(x);MemoryData.put(c,"reps",2);MemoryData.put(c,"state",MemoryScheduler.REVIEW);MemoryData.put(c,"stability",value);}
        int[] bands=MemoryStats.stabilityBands(m,Collections.singleton(d));ok(Arrays.equals(bands,new int[]{1,1,1,1,1}),"five disjoint stability edges exclude new cards");
        m.deleteDeck(d,NOW);ok(Arrays.equals(MemoryStats.stabilityBands(m,ALL),new int[5]),"deleted reviews excluded");
    }
    private static void calendar(){
        MemoryData m=new MemoryData();String d=deck(m),id=card(m,d,"calendar");
        long leap=date(2024,3,1,12);log(m,d,id,date(2024,2,29,12),3);
        MemoryStats.PeriodSeries months=MemoryStats.periods(m,leap,MemoryStats.Period.MONTH,2,ALL);
        ok(months.reviews[0]==1&&months.labels[0].equals("2024/02"),"leap-day belongs to February");
        MemoryStats.PeriodSeries weeks=MemoryStats.periods(m,date(2026,1,1,12),MemoryStats.Period.WEEK,2,ALL);
        ok(weeks.starts[1]==date(2025,12,29,0),"cross-year week starts Monday");
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        MemoryStats.PeriodSeries spring=MemoryStats.periods(m,date(2026,3,9,12),MemoryStats.Period.DAY,3,ALL);
        ok(spring.ends[1]-spring.starts[1]==23*3600000L,"spring DST day is 23 hours");
        MemoryStats.PeriodSeries fall=MemoryStats.periods(m,date(2026,11,2,12),MemoryStats.Period.DAY,3,ALL);
        ok(fall.ends[1]-fall.starts[1]==25*3600000L,"fall DST day is 25 hours");TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }
    private static void large(){MemoryData m=new MemoryData();String d=deck(m);for(int i=0;i<30000;i++){String id=card(m,d,"bulk "+i);if(i%7==0)MemoryData.put(m.card(id),"lapses",3);}long start=System.nanoTime();int count=MemoryCardQuery.count(m,MemoryCardQuery.Filter.DIFFICULT,ALL,NOW);List<String> page=MemoryCardQuery.page(m,MemoryCardQuery.Filter.DIFFICULT,ALL,NOW,50,50);MemoryStats.periods(m,NOW,MemoryStats.Period.DAY,30,ALL);ok(count==4286&&page.size()==50,"30000-card fixture pages and counts complete");System.out.println("Memory Momo 30000-card query+periods: "+((System.nanoTime()-start)/1000000)+" ms (desktop JVM, not device UI)");}
}
