package org.infinispan.query.backend;

import org.hibernate.search.backend.TransactionContext;
import org.hibernate.search.backend.spi.Work;
import org.hibernate.search.backend.spi.WorkType;
import org.hibernate.search.backend.spi.Worker;
import org.hibernate.search.spi.SearchIntegrator;
import org.infinispan.Cache;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.LocalFlagAffectedCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.container.DataContainer;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.KnownComponentNames;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.marshall.core.MarshalledValue;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryModifiedEvent;
import org.infinispan.query.Transformer;
import org.infinispan.query.impl.DefaultSearchWorkCreator;
import org.infinispan.query.logging.Log;
import org.infinispan.registry.ClusterRegistry;
import org.infinispan.registry.ScopedKey;
import org.infinispan.util.logging.LogFactory;

import javax.transaction.TransactionManager;
import javax.transaction.TransactionSynchronizationRegistry;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This interceptor will be created when the System Property "infinispan.query.indexLocalOnly" is "false"
 * <p/>
 * This type of interceptor will allow the indexing of data even when it comes from other caches within a cluster.
 * <p/>
 * However, if the a cache would not be putting the data locally, the interceptor will not index it.
 *
 * @author Navin Surtani
 * @author Sanne Grinovero <sanne@hibernate.org> (C) 2011 Red Hat Inc.
 * @author Marko Luksa
 * @author anistor@redhat.com
 * @since 4.0
 */
public final class QueryInterceptor extends CommandInterceptor {

   private final IndexModificationStrategy indexingMode;
   private final SearchIntegrator searchFactory;
   private final KeyTransformationHandler keyTransformationHandler = new KeyTransformationHandler();
   private final KnownClassesRegistryListener registryListener = new KnownClassesRegistryListener();
   private final AtomicBoolean stopping = new AtomicBoolean(false);

   private ReadIntensiveClusterRegistryWrapper<String, Class<?>, Boolean> clusterRegistry;

   private SearchWorkCreator<Object> searchWorkCreator = new DefaultSearchWorkCreator<>();

   private SearchFactoryHandler searchFactoryHandler;

   private DataContainer dataContainer;
   protected TransactionManager transactionManager;
   protected TransactionSynchronizationRegistry transactionSynchronizationRegistry;
   protected ExecutorService asyncExecutor;

   private static final Log log = LogFactory.getLog(QueryInterceptor.class, Log.class);

   @Override
   protected Log getLog() {
      return log;
   }

   public QueryInterceptor(SearchIntegrator searchFactory, IndexModificationStrategy indexingMode) {
      this.searchFactory = searchFactory;
      this.indexingMode = indexingMode;
   }

   @Inject
   @SuppressWarnings("unused")
   protected void injectDependencies(TransactionManager transactionManager,
                                     TransactionSynchronizationRegistry transactionSynchronizationRegistry,
                                     Cache cache,
                                     ClusterRegistry<String, Class<?>, Boolean> clusterRegistry,
                                     DataContainer dataContainer,
                                     @ComponentName(KnownComponentNames.ASYNC_TRANSPORT_EXECUTOR) ExecutorService e) {
      this.transactionManager = transactionManager;
      this.transactionSynchronizationRegistry = transactionSynchronizationRegistry;
      this.asyncExecutor = e;
      this.dataContainer = dataContainer;
      this.clusterRegistry = new ReadIntensiveClusterRegistryWrapper(clusterRegistry, "QueryKnownClasses#" + cache.getName());
      this.searchFactoryHandler = new SearchFactoryHandler(this.searchFactory, this.clusterRegistry, new TransactionHelper(transactionManager));
   }

   @Start
   protected void start() {
      clusterRegistry.addListener(registryListener);
      Set<Class<?>> keys = clusterRegistry.keys();
      Class<?>[] array = keys.toArray(new Class<?>[keys.size()]);
      //Important to enable them all in a single call, much more efficient:
      enableClasses(array);
      stopping.set(false);
   }

   @Stop
   protected void stop() {
      clusterRegistry.removeListener(registryListener);
   }

   public void prepareForStopping() {
      stopping.set(true);
   }

   @Listener
   class KnownClassesRegistryListener {

      @CacheEntryCreated
      public void created(CacheEntryCreatedEvent<ScopedKey<String, Class>, Boolean> e) {
         if (!e.isOriginLocal() && !e.isPre() && e.getValue()) {
            searchFactoryHandler.handleClusterRegistryRegistration(e.getKey().getKey());
         }
      }

      @CacheEntryModified
      public void modified(CacheEntryModifiedEvent<ScopedKey<String, Class>, Boolean> e) {
         if (!e.isOriginLocal() && !e.isPre() && e.getValue()) {
            searchFactoryHandler.handleClusterRegistryRegistration(e.getKey().getKey());
         }
      }
   }

   protected boolean shouldModifyIndexes(FlagAffectedCommand command, InvocationContext ctx) {
      return indexingMode.shouldModifyIndexes(command, ctx);
   }

   /**
    * Use this executor for Async operations
    * @return
    */
   public ExecutorService getAsyncExecutor() {
      return asyncExecutor;
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      Object toReturn = invokeNextInterceptor(ctx, command);
      processPutKeyValueCommand(command, ctx, toReturn, null);
      return toReturn;
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      // remove the object out of the cache first.
      Object valueRemoved = invokeNextInterceptor(ctx, command);
      processRemoveCommand(command, ctx, valueRemoved, null);
      return valueRemoved;
   }

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      Object valueReplaced = invokeNextInterceptor(ctx, command);
      processReplaceCommand(command, ctx, valueReplaced, null);
      return valueReplaced;
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      Map<Object, Object> previousValues = (Map<Object, Object>) invokeNextInterceptor(ctx, command);
      processPutMapCommand(command, ctx, previousValues, null);
      return previousValues;
   }

   @Override
   public Object visitClearCommand(final InvocationContext ctx, final ClearCommand command) throws Throwable {
      // This method is called when somebody calls a cache.clear() and we will need to wipe everything in the indexes.
      Object returnValue = invokeNextInterceptor(ctx, command);
      processClearCommand(command, ctx, null);
      return returnValue;
   }

   /**
    * Remove all entries from all known indexes
    */
   public void purgeAllIndexes() {
      purgeAllIndexes(null);
   }

   public void purgeIndex(Class<?> entityType) {
      purgeIndex(null, entityType);
   }

   private void purgeIndex(TransactionContext transactionContext, Class<?> entityType) {
      transactionContext = transactionContext == null ? makeTransactionalEventContext() : transactionContext;
      if(clusterRegistry.get(entityType) && searchFactoryHandler.isIndexed(entityType)) {
         performSearchWorks(searchWorkCreator.createPerEntityTypeWorks((Class<Object>) entityType, WorkType.PURGE_ALL), transactionContext);
      }

   }

   private void purgeAllIndexes(TransactionContext transactionContext) {
      transactionContext = transactionContext == null ? makeTransactionalEventContext() : transactionContext;
      for (Class c : clusterRegistry.keys()) {
         if (searchFactoryHandler.isIndexed(c)) {
            //noinspection unchecked
            performSearchWorks(searchWorkCreator.createPerEntityTypeWorks(c, WorkType.PURGE_ALL), transactionContext);
         }
      }
   }

   // Method that will be called when data needs to be removed from Lucene.
   protected void removeFromIndexes(final Object value, final Object key, final TransactionContext transactionContext) {
      performSearchWork(value, keyToString(key), WorkType.DELETE, transactionContext);
   }

   protected void updateIndexes(final boolean usingSkipIndexCleanupFlag, final Object value, final Object key, final TransactionContext transactionContext) {
      // Note: it's generally unsafe to assume there is no previous entry to cleanup: always use UPDATE
      // unless the specific flag is allowing this.
      performSearchWork(value, keyToString(key), usingSkipIndexCleanupFlag ? WorkType.ADD : WorkType.UPDATE, transactionContext);
   }

   private void performSearchWork(Object value, Serializable id, WorkType workType, TransactionContext transactionContext) {
      if (value == null) throw new NullPointerException("Cannot handle a null value!");
      Collection<Work> works = searchWorkCreator.createPerEntityWorks(value, id, workType);
      performSearchWorks(works, transactionContext);
   }

   private void performSearchWorks(Collection<Work> works, TransactionContext transactionContext) {
      Worker worker = searchFactory.getWorker();
      for (Work work : works) {
         worker.performWork(work, transactionContext);
      }
   }

   public boolean isIndexed(final Class<?> c) {
      return searchFactoryHandler.isIndexed(c);
   }

   private Object extractValue(Object wrappedValue) {
      if (wrappedValue instanceof MarshalledValue)
         return ((MarshalledValue) wrappedValue).get();
      else
         return wrappedValue;
   }

   public void enableClasses(Class[] classes) {
      searchFactoryHandler.enableClasses(classes);
   }

   public boolean updateKnownTypesIfNeeded(Object value) {
      return searchFactoryHandler.updateKnownTypesIfNeeded(value);
   }

   public void registerKeyTransformer(Class<?> keyClass, Class<? extends Transformer> transformerClass) {
      keyTransformationHandler.registerTransformer(keyClass, transformerClass);
   }

   private String keyToString(Object key) {
      return keyTransformationHandler.keyToString(key);
   }

   public KeyTransformationHandler getKeyTransformationHandler() {
      return keyTransformationHandler;
   }

   public SearchIntegrator getSearchFactory() {
      return searchFactory;
   }

   /**
    * Customize work creation during indexing
    * @param searchWorkCreator custom {@link org.infinispan.query.backend.SearchWorkCreator} 
    */
   public void setSearchWorkCreator(SearchWorkCreator<Object> searchWorkCreator) {
      this.searchWorkCreator = searchWorkCreator;
   }

   public SearchWorkCreator<Object> getSearchWorkCreator() {
      return searchWorkCreator;
   }

   /**
    * In case of a remotely originating transactions we don't have a chance to visit the single
    * commands but receive this "batch". We then need the before-apply snapshot of some types
    * to route the cleanup commands to the correct indexes.
    * Note we don't need to visit the CommitCommand as the indexing context is registered
    * as a transaction sync.
    */
   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      final WriteCommand[] writeCommands = command.getModifications();
      final Object[] stateBeforePrepare = new Object[writeCommands.length];

      for (int i = 0; i < writeCommands.length; i++) {
         final WriteCommand writeCommand = writeCommands[i];
         if (writeCommand instanceof PutKeyValueCommand) {
            InternalCacheEntry internalCacheEntry = dataContainer.get(((PutKeyValueCommand) writeCommand).getKey());
            stateBeforePrepare[i] = internalCacheEntry != null ? internalCacheEntry.getValue() : null;
         } else if (writeCommand instanceof PutMapCommand) {
            stateBeforePrepare[i] = getPreviousValues(((PutMapCommand) writeCommand).getMap().keySet());
         } else if (writeCommand instanceof RemoveCommand) {
            InternalCacheEntry internalCacheEntry = dataContainer.get(((RemoveCommand) writeCommand).getKey());
            stateBeforePrepare[i] = internalCacheEntry != null ? internalCacheEntry.getValue() : null;
         } else if (writeCommand instanceof ReplaceCommand) {
            InternalCacheEntry internalCacheEntry = dataContainer.get(((ReplaceCommand) writeCommand).getKey());
            stateBeforePrepare[i] = internalCacheEntry != null ? internalCacheEntry.getValue() : null;
         }
      }

      final Object toReturn = super.visitPrepareCommand(ctx, command);

      if (ctx.isTransactionValid()) {
         final TransactionContext transactionContext = makeTransactionalEventContext();
         for (int i = 0; i < writeCommands.length; i++) {
            final WriteCommand writeCommand = writeCommands[i];
            if (writeCommand instanceof PutKeyValueCommand) {
               processPutKeyValueCommand((PutKeyValueCommand) writeCommand, ctx, stateBeforePrepare[i], transactionContext);
            } else if (writeCommand instanceof PutMapCommand) {
               processPutMapCommand((PutMapCommand) writeCommand, ctx, (Map<Object, Object>) stateBeforePrepare[i], transactionContext);
            } else if (writeCommand instanceof RemoveCommand) {
               processRemoveCommand((RemoveCommand) writeCommand, ctx, stateBeforePrepare[i], transactionContext);
            } else if (writeCommand instanceof ReplaceCommand) {
               processReplaceCommand((ReplaceCommand) writeCommand, ctx, stateBeforePrepare[i], transactionContext);
            } else if (writeCommand instanceof ClearCommand) {
               processClearCommand((ClearCommand) writeCommand, ctx, transactionContext);
            }
         }
      }
      return toReturn;
   }

   private Map<Object, Object> getPreviousValues(Set<Object> keySet) {
      HashMap<Object, Object> previousValues = new HashMap<Object, Object>();
      for (Object key : keySet) {
         InternalCacheEntry internalCacheEntry = dataContainer.get(key);
         Object previousValue = internalCacheEntry != null ? internalCacheEntry.getValue() : null;
         previousValues.put(key, previousValue);
      }
      return previousValues;
   }

   /**
    * Indexing management of a ReplaceCommand
    *
    * @param command the ReplaceCommand
    * @param ctx the InvocationContext
    * @param valueReplaced the previous value on this key
    * @param transactionContext Optional for lazy initialization, or reuse an existing context.
    */
   private void processReplaceCommand(final ReplaceCommand command, final InvocationContext ctx, final Object valueReplaced, TransactionContext transactionContext) {
      if (valueReplaced != null && command.isSuccessful() && shouldModifyIndexes(command, ctx)) {
         final boolean usingSkipIndexCleanupFlag = usingSkipIndexCleanup(command);
         Object[] parameters = command.getParameters();
         Object p2 = extractValue(parameters[2]);
         final boolean newValueIsIndexed = updateKnownTypesIfNeeded(p2);
         Object key = extractValue(command.getKey());

         if (!usingSkipIndexCleanupFlag) {
            final Object p1 = extractValue(parameters[1]);
            final boolean originalIsIndexed = updateKnownTypesIfNeeded(p1);
            if (p1 != null && originalIsIndexed) {
               transactionContext = transactionContext == null ? makeTransactionalEventContext() : transactionContext;
               removeFromIndexes(p1, key, transactionContext);
            }
         }
         if (newValueIsIndexed) {
            transactionContext = transactionContext == null ? makeTransactionalEventContext() : transactionContext;
            updateIndexes(usingSkipIndexCleanupFlag, p2, key, transactionContext);
         }
      }
   }

   /**
    * Indexing management of a RemoveCommand
    *
    * @param command the visited RemoveCommand
    * @param ctx the InvocationContext of the RemoveCommand
    * @param valueRemoved the value before the removal
    * @param transactionContext Optional for lazy initialization, or reuse an existing context.
    */
   private void processRemoveCommand(final RemoveCommand command, final InvocationContext ctx, final Object valueRemoved, TransactionContext transactionContext) {
      if (command.isSuccessful() && !command.isNonExistent() && shouldModifyIndexes(command, ctx)) {
         final Object value = extractValue(valueRemoved);
         if (updateKnownTypesIfNeeded(value)) {
            transactionContext = transactionContext == null ? makeTransactionalEventContext() : transactionContext;
            removeFromIndexes(value, extractValue(command.getKey()), transactionContext);
         }
      }
   }

   /**
    * Indexing management of a PutMapCommand
    *
    * @param command the visited PutMapCommand
    * @param ctx the InvocationContext of the PutMapCommand
    * @param previousValues a map with the previous values, before processing the given PutMapCommand
    * @param transactionContext
    */
   private void processPutMapCommand(final PutMapCommand command, final InvocationContext ctx, final Map<Object, Object> previousValues, TransactionContext transactionContext) {
      if (shouldModifyIndexes(command, ctx)) {
         Map<Object, Object> dataMap = command.getMap();
         final boolean usingSkipIndexCleanupFlag = usingSkipIndexCleanup(command);
         // Loop through all the keys and put those key-value pairings into lucene.
         for (Map.Entry<Object, Object> entry : dataMap.entrySet()) {
            final Object key = extractValue(entry.getKey());
            final Object value = extractValue(entry.getValue());
            final Object previousValue = previousValues.get(key);
            if (!usingSkipIndexCleanupFlag && updateKnownTypesIfNeeded(previousValue)) {
               transactionContext = transactionContext == null ? makeTransactionalEventContext() : transactionContext;
               removeFromIndexes(previousValue, key, transactionContext);
            }
            if (updateKnownTypesIfNeeded(value)) {
               transactionContext = transactionContext == null ? makeTransactionalEventContext() : transactionContext;
               updateIndexes(usingSkipIndexCleanupFlag, value, key, transactionContext);
            }
         }
      }
   }

   /**
    * Indexing management of a PutKeyValueCommand
    *
    * @param command the visited PutKeyValueCommand
    * @param ctx the InvocationContext of the PutKeyValueCommand
    * @param previousValue the value being replaced by the put operation
    * @param transactionContext Optional for lazy initialization, or reuse an existing context.
    */
   private void processPutKeyValueCommand(final PutKeyValueCommand command, final InvocationContext ctx, final Object previousValue, TransactionContext transactionContext) {
      final boolean usingSkipIndexCleanupFlag = usingSkipIndexCleanup(command);
      //whatever the new type, we might still need to cleanup for the previous value (and schedule removal first!)
      Object value = extractValue(command.getValue());
      if (!usingSkipIndexCleanupFlag && updateKnownTypesIfNeeded(previousValue) && shouldRemove(value, previousValue)) {
         if (shouldModifyIndexes(command, ctx)) {
            transactionContext = transactionContext == null ? makeTransactionalEventContext() : transactionContext;
            removeFromIndexes(previousValue, extractValue(command.getKey()), transactionContext);
         }
      }
      if (updateKnownTypesIfNeeded(value)) {
         if (shouldModifyIndexes(command, ctx)) {
            // This means that the entry is just modified so we need to update the indexes and not add to them.
            transactionContext = transactionContext == null ? makeTransactionalEventContext() : transactionContext;
            updateIndexes(usingSkipIndexCleanupFlag, value, extractValue(command.getKey()), transactionContext);
         }
      }
   }

   private boolean shouldRemove(Object value, Object previousValue) {
      return !(value == null || previousValue == null) && !value.getClass().equals(previousValue.getClass());
   }

   /**
    * Indexing management of the Clear command
    *
    * @param command the ClearCommand
    * @param ctx the InvocationContext of the PutKeyValueCommand
    * @param transactionContext Optional for lazy initialization, or to reuse an existing transactional context.
    */
   private void processClearCommand(final ClearCommand command, final InvocationContext ctx, TransactionContext transactionContext) {
      if (shouldModifyIndexes(command, ctx)) {
         purgeAllIndexes(transactionContext);
      }
   }

   private TransactionContext makeTransactionalEventContext() {
      return new TransactionalEventTransactionContext(transactionManager, transactionSynchronizationRegistry);
   }

   private boolean usingSkipIndexCleanup(final LocalFlagAffectedCommand command) {
      return command != null && command.hasFlag(Flag.SKIP_INDEX_CLEANUP);
   }

   public IndexModificationStrategy getIndexModificationMode() {
      return indexingMode;
   }

   public boolean isStopping() {
      return stopping.get();
   }

}
