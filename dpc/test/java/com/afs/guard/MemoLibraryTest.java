package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure metadata tests. No Android runtime, devices, files, or note databases. */
public final class MemoLibraryTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void rejects(Runnable action,String label){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,label);}
    private static JSONObject copy(JSONObject json){try{return new JSONObject(json.toString());}catch(Exception e){throw new RuntimeException(e);}}
    public static void main(String[] args)throws Exception{
        MemoLibrary.Model model=new MemoLibrary.Model();
        check(model.notebooks.isEmpty()&&!model.grid,"default library");
        check(MemoLibrary.notebookOf(model,"note-1").equals(""),"default notebook uses empty id");
        check(MemoLibrary.paperOf(model,"note-1").equals("default"),"default paper");
        check(MemoLibrary.notebookOf(model,null).isEmpty()&&MemoLibrary.paperOf(model,"").equals("default"),"unsaved draft reads default metadata");
        MemoLibrary.Book first=MemoLibrary.createNotebook(model,"  工程  "),second=MemoLibrary.createNotebook(model,"生活");
        check(first.name.equals("工程")&&first.id.length()==36&&!first.id.equals(second.id),"UUID custom books and trimmed names");
        rejects(()->MemoLibrary.createNotebook(model,"工程"),"duplicate name rejected");
        rejects(()->MemoLibrary.createNotebook(model,"默认笔记本"),"reserved default name rejected");
        rejects(()->MemoLibrary.createNotebook(model," "),"blank name rejected");
        rejects(()->MemoLibrary.createNotebook(model,"名字\n换行"),"multiline name rejected");
        MemoLibrary.renameNotebook(model,first.id,"大学");check(first.name.equals("大学"),"rename custom book");
        rejects(()->MemoLibrary.renameNotebook(model,first.id,"生活"),"rename cannot collide");
        rejects(()->MemoLibrary.renameNotebook(model,"","名称"),"default cannot rename");
        rejects(()->MemoLibrary.deleteNotebook(model,""),"default cannot delete");
        MemoLibrary.setNotebook(model,"note-1",first.id);MemoLibrary.setNotebook(model,"note-2",second.id);
        MemoLibrary.setPaper(model,"note-1","cream");MemoLibrary.setPaper(model,"note-2","blue");model.grid=true;
        check(MemoLibrary.notebookOf(model,"note-1").equals(first.id),"assign note to notebook");
        rejects(()->MemoLibrary.setNotebook(model,"note-1","unknown"),"unknown destination rejected");
        rejects(()->MemoLibrary.setPaper(model,"note-1","remote-image"),"unknown paper rejected");
        rejects(()->MemoLibrary.setPaper(model,"bad\nid","green"),"control in note id rejected");
        JSONObject json=MemoLibrary.toJson(model);MemoLibrary.Model roundtrip=MemoLibrary.validate(json);
        check(roundtrip.grid&&roundtrip.notebooks.size()==2,"roundtrip layout and books");
        check(MemoLibrary.notebookOf(roundtrip,"note-1").equals(first.id)&&MemoLibrary.paperOf(roundtrip,"note-2").equals("blue"),"roundtrip assignments and paper");
        roundtrip.notebooks.get(0).name="独立";check(model.notebooks.get(0).name.equals("大学"),"decoded model detached");
        MemoLibrary.deleteNotebook(model,first.id);
        check(MemoLibrary.notebookOf(model,"note-1").isEmpty()&&model.notebooks.size()==1,"deletion returns mapped notes to default");
        check(MemoLibrary.paperOf(model,"note-1").equals("cream")&&MemoLibrary.notebookOf(model,"note-2").equals(second.id),"deletion preserves other metadata");
        MemoLibrary.setPaper(model,"note-1","default");check(!model.papers.containsKey("note-1"),"default paper removes unnecessary mapping");
        MemoLibrary.setNotebook(model,"note-2","");check(!model.assignments.containsKey("note-2"),"explicit default removes notebook mapping");
        JSONObject bad=copy(json);bad.put("version",2);rejects(()->MemoLibrary.validate(bad),"future schema rejected");
        JSONObject badType=copy(json);badType.put("grid","false");rejects(()->MemoLibrary.validate(badType),"wrong primitive type rejected");
        JSONObject orphan=copy(json);orphan.getJSONObject("assignments").put("note-3","missing");rejects(()->MemoLibrary.validate(orphan),"orphan notebook import rejected");
        JSONObject duplicate=copy(json);duplicate.getJSONArray("notebooks").put(duplicate.getJSONArray("notebooks").getJSONObject(0));rejects(()->MemoLibrary.validate(duplicate),"duplicate notebook id rejected");
        JSONObject wrongPaper=copy(json);wrongPaper.getJSONObject("papers").put("note-3","red");rejects(()->MemoLibrary.validate(wrongPaper),"invalid imported paper rejected");
        JSONObject missing=copy(json);missing.remove("notebooks");rejects(()->MemoLibrary.validate(missing),"missing field rejected");
        JSONObject wrongId=copy(json);wrongId.getJSONArray("notebooks").getJSONObject(0).put("id","not-a-uuid");rejects(()->MemoLibrary.validate(wrongId),"invalid imported book id rejected");
        JSONObject badMap=copy(json);badMap.getJSONObject("assignments").put("note-3",true);rejects(()->MemoLibrary.validate(badMap),"wrong mapping value type rejected");
        MemoLibrary.Model full=new MemoLibrary.Model();for(int i=0;i<100;i++)MemoLibrary.createNotebook(full,"Book "+i);
        rejects(()->MemoLibrary.createNotebook(full,"overflow"),"notebook cap rejects without deleting any");
        check(full.notebooks.size()==100,"overflow preserves existing books");
        MemoLibrary.Model links=new MemoLibrary.Model();MemoLibrary.Book book=MemoLibrary.createNotebook(links,"目标");
        for(int i=0;i<1000;i++)MemoLibrary.setNotebook(links,"note-"+i,book.id);
        rejects(()->MemoLibrary.setNotebook(links,"overflow",book.id),"assignment cap");
        check(links.assignments.size()==1000,"assignment cap preserves existing mappings");
        check(MemoLibrary.validate(MemoLibrary.toJson(new MemoLibrary.Model())).notebooks.isEmpty(),"empty backup roundtrip");
        MemoLibrary.Model local=MemoLibrary.validate(json),incoming=MemoLibrary.validate(json);incoming.grid=false;
        incoming.papers.put("note-1","green");MemoLibrary.setNotebook(incoming,"note-new",second.id);
        MemoLibrary.Model merged=MemoLibrary.merge(local,incoming);
        check(merged.notebooks.size()==2&&merged.grid,"matching books reused and local layout retained");
        check(MemoLibrary.paperOf(merged,"note-1").equals("cream"),"merge never overwrites existing paper");
        check(MemoLibrary.notebookOf(merged,"note-new").equals(second.id),"merge retains new note mapping");
        incoming.notebooks.get(0).name="冲突笔记本";
        incoming.assignments.put("new-conflict",first.id);incoming.assignments.put("note-2",first.id);
        merged=MemoLibrary.merge(local,incoming);
        check(merged.notebooks.size()==3,"same id different name creates another book");
        String replacement=MemoLibrary.notebookOf(merged,"new-conflict");
        check(!replacement.equals(first.id)&&merged.notebooks.get(2).id.equals(replacement),"new mapping remapped to conflict UUID");
        check(MemoLibrary.notebookOf(merged,"note-2").equals(second.id),"existing local note assignment wins");
        check(local.notebooks.size()==2&&local.assignments.size()==2,"pure merge does not mutate local model");
        MemoLibrary.Model nameConflict=new MemoLibrary.Model();MemoLibrary.Book sameName=MemoLibrary.createNotebook(nameConflict,"大学");
        MemoLibrary.setNotebook(nameConflict,"another",sameName.id);MemoLibrary.Model namesMerged=MemoLibrary.merge(local,nameConflict);
        check(namesMerged.notebooks.size()==3&&namesMerged.notebooks.get(2).name.equals("大学（导入）"),"duplicate name safely renamed on merge");
        rejects(()->MemoLibrary.merge(full,nameConflict),"full library merge rejected without destructive eviction");
        System.out.println("Memo library: "+checks+" checks passed");
    }
}
