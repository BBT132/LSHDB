/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gr.eap.LSHDB;

import gr.eap.LSHDB.util.QueryRecord;
import gr.eap.LSHDB.util.Result;
import gr.eap.LSHDB.util.Record;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import gr.eap.LSHDB.client.Client;
import gr.eap.LSHDB.util.ListUtil;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import org.apache.log4j.Logger;

/**
 *
 * @author dimkar
 */
public abstract class DataStore {
    final static Logger log = Logger.getLogger(DataStore.class);
    
    
    String folder;
    String dbName;
    String dbEngine;
    public String pathToDB;
    public StoreEngine data;
    public StoreEngine keys;
    public StoreEngine records;
    HashMap<String, StoreEngine> keyMap = new HashMap<String, StoreEngine>();
    HashMap<String, StoreEngine> dataMap = new HashMap<String, StoreEngine>();
    ArrayList<Node> nodes = new ArrayList<Node>();
    boolean queryRemoteNodes = false;
    boolean massInsertMode = false;

    public final static String KEYS = "keys";
    public final static String DATA = "data";
    public final static String CONF = "conf";
    public final static String RECORDS = "records";
    
    
    
    public void setMassInsertMode(boolean status) {
        massInsertMode = status;
    }

    public boolean getMassInsertMode() {
        return massInsertMode;
    }

    public void setQueryMode(boolean status) {
        queryRemoteNodes = status;
    }

    public boolean getQueryMode() {
        return queryRemoteNodes;
    }

    public Node getNode(String alias) {
        for (int i = 0; i < this.getNodes().size(); i++) {
            Node node = this.getNodes().get(i);
            if (node.alias.equals(alias)) {
                return node;
            }
        }
        return null;
    }

    public ArrayList<Node> getNodes() {
        return this.nodes;
    }

    public void addNode(Node n) {
        this.nodes.add(n);
    }

    public StoreEngine getKeyMap(String fieldName) {
        fieldName = fieldName.replaceAll(" ", "");
        return keyMap.get(fieldName);
    }

    public void setKeyMap(String fieldName, boolean massInsertMode) throws NoSuchMethodException, ClassNotFoundException {
        fieldName = fieldName.replaceAll(" ", "");
        keyMap.put(fieldName, DataStoreFactory.build(folder, dbName, KEYS+"_" + fieldName, dbEngine, massInsertMode));
    }

    public StoreEngine getDataMap(String fieldName) {
        fieldName = fieldName.replaceAll(" ", "");
        return dataMap.get(fieldName);
    }

    public void setDataMap(String fieldName, boolean massInsertMode) throws NoSuchMethodException, ClassNotFoundException {
        fieldName = fieldName.replaceAll(" ", "");
        dataMap.put(fieldName, DataStoreFactory.build(folder, dbName, DATA+"_" + fieldName, dbEngine, massInsertMode));
    }

    /*
    public DataStore(StoreEngine db){
        if ((this.getConfiguration() != null) && (this.getConfiguration().isKeyed())) {
                String[] keyFieldNames = this.getConfiguration().getKeyFieldNames();
                for (int j = 0; j < keyFieldNames.length; j++) {
                    String keyFieldName = keyFieldNames[j];
                    
                }
            } else {
                keys = 
                data = 
                keyMap.put(Configuration.RECORD_LEVEL, keys);
                dataMap.put(Configuration.RECORD_LEVEL, data);

            }
    }*/
    
    public void init(String dbEngine, boolean massInsertMode) throws StoreInitException {
        try {
            this.dbEngine = dbEngine;
            pathToDB = folder + System.getProperty("file.separator") + dbName;
            records = DataStoreFactory.build(folder, dbName,  RECORDS, dbEngine, massInsertMode);
            if ((this.getConfiguration() != null) && (this.getConfiguration().isKeyed())) {
                String[] keyFieldNames = this.getConfiguration().getKeyFieldNames();
                for (int j = 0; j < keyFieldNames.length; j++) {
                    String keyFieldName = keyFieldNames[j];
                    setKeyMap(keyFieldName, massInsertMode);
                    setDataMap(keyFieldName, massInsertMode);
                }
            } else {
                keys = DataStoreFactory.build(folder, dbName, KEYS, dbEngine, massInsertMode);
                data = DataStoreFactory.build(folder, dbName, DATA, dbEngine, massInsertMode);
                keyMap.put(Configuration.RECORD_LEVEL, keys);
                dataMap.put(Configuration.RECORD_LEVEL, data);

            }
        } catch (ClassNotFoundException ex) {
            throw new StoreInitException("Declared class " + dbEngine + " not found.");
        } catch (NoSuchMethodException ex) {
            throw new StoreInitException("The particular constructor cannot be found in the decalred class " + dbEngine + ".");
        }
    }

    
    
  
    public void close() {

        records.close();
        if (this.getConfiguration().isKeyed()) {
            String[] keyFieldNames = this.getConfiguration().keyFieldNames;
            for (int j = 0; j < keyFieldNames.length; j++) {
                String indexFieldName = keyFieldNames[j];
                StoreEngine dataFactory = getKeyMap(indexFieldName);
                dataFactory.close();
                dataFactory = getDataMap(indexFieldName);
                dataFactory.close();
            }
        } else {
            data.close();
            keys.close();
        }
    }

   
    public String getDbName() {
        return this.dbName;
    }

    public String getDbEngine() {
        return this.dbEngine;
    }

    public Result forkQuery(QueryRecord queryRecord) {
        Result result = new Result(queryRecord); 
        if (this.getNodes().size() == 0) {
            return result;
        }
        // should implment get Active Nodes
        ExecutorService executorService = Executors.newFixedThreadPool(this.getNodes().size());
        List<Callable<Result>> callables = new ArrayList<Callable<Result>>();

        final QueryRecord q = queryRecord;
        for (int i = 0; i < this.getNodes().size(); i++) {
            final Node node = this.getNodes().get(i);
            if (node.isEnabled()) {
                callables.add(new Callable<Result>() {
                    public Result call()  {
                        Result r = null;        
                        if ((!node.isLocal()) && (q.isClientQuery())) {
                            Client client = new Client(node.url, node.port);
                            try {
                                QueryRecord newQuery = (QueryRecord) q.clone();
                                newQuery.setServerQuery();                                
                                r = client.queryServer(newQuery);
                                if (r == null) {
                                    r = new Result(newQuery);
                                    r.setStatus(Result.NULL_RESULT_RETURNED);
                                }                                
                                r.setRemote();
                                r.setOrigin(node.alias);                                
                            } catch (CloneNotSupportedException | NodeCommunicationException ex) {
                                if (r==null)                                    
                                    r=new Result(q);
                                r.setRemote();
                                r.setOrigin(node.alias);
                                r.setStatus(Result.NO_CONNECT);
                            }

                        } else if (node.isLocal()) {
                           try{ 
                              r = query(q);
                              r.setStatus(Result.STATUS_OK);
                              r.prepare();
                              r.setOrigin(node.alias);
                           }catch(NoKeyedFieldsException ex){
                                if (r!=null)                                    
                                    r=new Result(q);
                               r.setOrigin(node.alias);
                               r.setStatus(Result.NO_KEYED_FIELDS_SPECIFIED);
                           }   
                        }
                        return r;
                    }
                });
            }
        }

        Result partialResults = null;
        try {
            List<Future<Result>> futures = executorService.invokeAll(callables);

            for (Future<Result> future : futures) {
                    
                if (future != null) {  //partialResults should not come null
                    
                    partialResults = future.get();
                    if (partialResults != null) {
                        result.getRecords().addAll(partialResults.getRecords());
                        result.setStatus(partialResults.getOrigin() , partialResults.getStatus());
                    }
                }

            }
        } catch (ExecutionException | InterruptedException ex) {
            if (ex.getCause() != null) {
                String server=" ";
                if (partialResults!=null)
                    server = partialResults.getOrigin();
                log.error("forkQuery error ",ex);
                if (ex.getCause() instanceof Error) {
                    log.fatal("forkQuery Fatal error occurred on " + server,ex);                    
                    Node node = getNode(server);
                    if (node != null) {
                        node.disable();
                    }
                }

            }
        } 

        executorService.shutdown();
        return result;
    }

    

    public int getThreadsNo() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.getThreadCount();
    }

    public void forkHashTables(Embeddable struct1, QueryRecord queryRec, String keyFieldName, Result result) {
        final Configuration conf = this.getConfiguration();
        final int maxQueryRows = queryRec.getMaxQueryRows();
        final boolean performComparisons = queryRec.performComparisons(keyFieldName);
        final double userPercentageThreshold = queryRec.getUserPercentageThreshold(keyFieldName);
        final StoreEngine keys = this.getKeyMap(keyFieldName);
        final StoreEngine data = this.getDataMap(keyFieldName);
        final Key key = conf.getKey(keyFieldName);
        boolean isPrivateMode = conf.isPrivateMode();

        ExecutorService executorService = Executors.newFixedThreadPool(key.L);
        List<Callable<Result>> callables = new ArrayList<Callable<Result>>();

        final Result result1 = result;
        final String keyFieldName1 = keyFieldName;
        final Embeddable struct11 = struct1;

        for (int j = 0; j < key.L; j++) {
            final int hashTableNo = j;
            callables.add(new Callable<Result>() {
                public Result call() throws StoreInitException, NoKeyedFieldsException {
                    String hashKey = buildHashKey(hashTableNo, struct11, keyFieldName1);
                    if (keys.contains(hashKey)) {
                        ArrayList arr = (ArrayList) keys.get(hashKey);
                    
                        for (int i = 0; i < arr.size(); i++) {
                            String id = (String) arr.get(i);

                            CharSequence cSeq = Key.KEYFIELD;
                            String idRec = id;
                            if (idRec.contains(cSeq)) {
                                idRec = id.split(Key.KEYFIELD)[0];
                            }
                            Record dataRec = null;
                            if (!conf.isPrivateMode()) {
                                dataRec = (Record) records.get(idRec);   // which id and which record shoudl strip the "_keyField_" part , if any
                            } else {
                                dataRec = new Record();
                                dataRec.setId(idRec);
                            }
                            result1.incPairsNo();
                            if ((performComparisons) && (!result1.getMap(keyFieldName1).containsKey(id))) {
                                Embeddable struct2 = (Embeddable) data.get(id);
                                key.thresholdRatio = userPercentageThreshold;
                                if (distance(struct11, struct2, key)) {
                                    result1.add(keyFieldName1, dataRec);
                                    int matchesNo = result1.getDataRecordsSize(keyFieldName1);
                                    if (matchesNo >= maxQueryRows) {
                                        return result1;
                                    }
                                } else {
                                }

                            } else {
                                result1.add(keyFieldName1, dataRec);
                            }

                        }
                    }
                    return result1;
                }
            });
        }

        try {
            List<Future<Result>> futures = executorService.invokeAll(callables);
            int k = 0;
            for (Future<Result> future : futures) {

                if (future != null) {
                    Result partialResults = future.get();

                    k++;
                    if (partialResults != null) {
                        result.getRecords().addAll(partialResults.getRecords());
                    }
                }
            }
        } catch (ExecutionException | InterruptedException ex) {
            log.error("forkHashTables ",ex);
        } 
        executorService.shutdown();

    }

    public void setHashKeys(String id, Embeddable emb, String keyFieldName) {
        boolean isKeyed = this.getConfiguration().isKeyed();
        String[] keyFieldNames = this.getConfiguration().getKeyFieldNames();
        StoreEngine hashKeys = keys;
        if (isKeyed) {
            hashKeys = this.getKeyMap(keyFieldName);
        }

        Key key = this.getConfiguration().getKey(keyFieldName);

        for (int j = 0; j < key.L; j++) {
            String hashKey = buildHashKey(j, emb, keyFieldName);
            ArrayList<String> arr = new ArrayList<String>();

            if (hashKeys.contains(hashKey)) {
                arr = (ArrayList) hashKeys.get(hashKey);
                arr.add(id);

            } else {
                arr.add(id);
            }
            hashKeys.set(hashKey, arr);
        }
    }

    public void insert(Record rec) {
        if (this.getConfiguration().isPrivateMode()) {
            Embeddable emb = (Embeddable) rec.get(Record.PRIVATE_STRUCTURE);
            data.set(rec.getId(), emb);
            setHashKeys(rec.getId(), emb, Configuration.RECORD_LEVEL);
            return;
        }

        boolean isKeyed = this.getConfiguration().isKeyed();
        String[] keyFieldNames = this.getConfiguration().getKeyFieldNames();
        HashMap<String, ? extends Embeddable[]> embMap = buildEmbeddableMap(rec);

        if (isKeyed) {
            for (int i = 0; i < keyFieldNames.length; i++) {
                String keyFieldName = keyFieldNames[i];
                Embeddable[] embs = embMap.get(keyFieldName);
                for (int j = 0; j < embs.length; j++) {
                    Embeddable emb = embs[j];
                    setHashKeys(rec.getId() + Key.KEYFIELD + j, emb, keyFieldName);
                    this.getDataMap(keyFieldName).set(rec.getId() + Key.KEYFIELD + j, emb);
                }

            }
        } else {
            data.set(rec.getId(), ((Embeddable[]) embMap.get(Configuration.RECORD_LEVEL))[0]);
            setHashKeys(rec.getId(), ((Embeddable[]) embMap.get(Configuration.RECORD_LEVEL))[0], Configuration.RECORD_LEVEL);
        }

        records.set(rec.getId(), rec);
    }

    public Result query(QueryRecord queryRecord) throws NoKeyedFieldsException {
        Result result = new Result(queryRecord);        
        Configuration conf = this.getConfiguration();
        StoreEngine hashKeys = keys;
        StoreEngine dataKeys = data;
        HashMap<String, ? extends Embeddable[]> embMap = null;
        if (!conf.isPrivateMode()) {
            embMap = buildEmbeddableMap(queryRecord);
        }
        boolean isKeyed = this.getConfiguration().isKeyed();
        String[] keyFieldNames = this.getConfiguration().getKeyFieldNames();
        ArrayList<String> fieldNames = queryRecord.getFieldNames();

        if ((fieldNames.isEmpty()) && (conf.isKeyed)) {
            throw new NoKeyedFieldsException(Result.NO_KEYED_FIELDS_SPECIFIED_ERROR_MSG);
        }
        if (ListUtil.intersection(fieldNames, Arrays.asList(keyFieldNames)).isEmpty() && (conf.isKeyed)) {
            throw new NoKeyedFieldsException(Result.NO_KEYED_FIELDS_SPECIFIED_ERROR_MSG);
        }

        for (int i = 0; i < fieldNames.size(); i++) {
            String fieldName = fieldNames.get(i);
            if (keyFieldNames != null) {
                for (int j = 0; j < keyFieldNames.length; j++) {
                    String keyFieldName = keyFieldNames[j];
                    if (keyFieldName.equals(fieldName)) {
                        Embeddable[] structArr = embMap.get(fieldName);
                        for (int k = 0; k < structArr.length; k++) {
                            forkHashTables(structArr[k], queryRecord, keyFieldName, result);

                        }
                    }
                }
            }

        }

        if (!isKeyed) {
            Embeddable emb = null;
            if (conf.isPrivateMode()) {
                emb = (Embeddable) queryRecord.get(Record.PRIVATE_STRUCTURE);
            } else {
                emb = ((Embeddable[]) embMap.get(Configuration.RECORD_LEVEL))[0];
            }
            forkHashTables(emb, queryRecord, Configuration.RECORD_LEVEL, result);
        }

        return result;
    }
    

    public HashMap<String, Embeddable[]> buildEmbeddableMap(Record rec) {        
        
        HashMap<String, Embeddable[]> embMap = new HashMap<String, Embeddable[]>();
        boolean isKeyed = this.getConfiguration().isKeyed();
        String[] keyFieldNames = this.getConfiguration().getKeyFieldNames();
        ArrayList<String> fieldNames = rec.getFieldNames();        
        Embeddable embRec=null;
        if ((!isKeyed) && (this.getConfiguration().getKey(Configuration.RECORD_LEVEL)!=null)){ 
             embRec =  this.getConfiguration().getKey(Configuration.RECORD_LEVEL).getEmbeddable().freshCopy();
        }
        
        for (int i = 0; i < fieldNames.size(); i++) {
            String fieldName = fieldNames.get(i);
            boolean isNotIndexedField = rec.isNotIndexedField(fieldName);
            String s = (String) rec.get(fieldName);
            if (isKeyed) {
                for (int j = 0; j < keyFieldNames.length; j++) {
                    String keyFieldName = keyFieldNames[j];
                    if (keyFieldName.equals(fieldName)) {
                        Key key = this.getConfiguration().getKey(keyFieldName);
                        boolean isTokenized = key.isTokenized();                        
                        if (!isTokenized) {
                            Embeddable emb = key.getEmbeddable().freshCopy();                        
                            emb.embed(s);
                            embMap.put(keyFieldName, new Embeddable[]{emb});
                        } else {
                            String[] keyValues = (String[]) rec.get(keyFieldName + Key.TOKENS);
                            Embeddable[] bfs = new Embeddable[keyValues.length];
                            for (int k = 0; k < bfs.length; k++) {
                                String v = keyValues[k];
                                Embeddable emb = key.getEmbeddable().freshCopy();
                                emb.embed(v);                            
                                bfs[k] = emb;
                            }
                            embMap.put(keyFieldName, bfs);
                        }
                    }
                }
            } else if (!isNotIndexedField) {
               if (embRec!=null)                           
                  embRec.embed(s);
               else
                 log.error("Although no key fields are specified, a record-level embeddable is missing.");
            }
        }
        if (!isKeyed) {
            embMap.put(Configuration.RECORD_LEVEL, new Embeddable[]{embRec});
        }

        return embMap;
    }

    
    
    public abstract String buildHashKey(int j, Embeddable struct, String keyFieldName);

    public abstract boolean distance(Embeddable struct1, Embeddable struct2, Key key);

    
    
    public abstract Configuration getConfiguration();

}
