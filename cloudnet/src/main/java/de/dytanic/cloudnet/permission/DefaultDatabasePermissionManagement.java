package de.dytanic.cloudnet.permission;

import com.google.common.base.Preconditions;
import com.google.gson.reflect.TypeToken;
import de.dytanic.cloudnet.common.concurrent.*;
import de.dytanic.cloudnet.common.document.gson.JsonDocument;
import de.dytanic.cloudnet.database.AbstractDatabaseProvider;
import de.dytanic.cloudnet.database.IDatabase;
import de.dytanic.cloudnet.driver.permission.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public class DefaultDatabasePermissionManagement extends ClusterSynchronizedPermissionManagement
        implements DefaultSynchronizedPermissionManagement, DefaultPermissionManagement {

    private static final String DATABASE_USERS_NAME = "cloudnet_permission_users";

    private final File file = new File(System.getProperty("cloudnet.permissions.json.path", "local/permissions.json"));

    private final Map<String, IPermissionGroup> permissionGroupsMap = new ConcurrentHashMap<>();
    private final Callable<AbstractDatabaseProvider> databaseProviderCallable;
    private IPermissionManagementHandler permissionManagementHandler;

    public DefaultDatabasePermissionManagement(Callable<AbstractDatabaseProvider> databaseProviderCallable) {
        this.databaseProviderCallable = databaseProviderCallable;

        this.file.getParentFile().mkdirs();
        this.loadGroups();
    }

    @Override
    public ITask<IPermissionUser> addUserWithoutClusterSyncAsync(IPermissionUser permissionUser) {
        Preconditions.checkNotNull(permissionUser);

        CompletableTask<IPermissionUser> task = new CompletableTask<>();
        this.getDatabase().insertAsync(permissionUser.getUniqueId().toString(), new JsonDocument(permissionUser))
                .onComplete(success -> task.complete(permissionUser))
                .onCancelled(booleanITask -> task.cancel(true))
                .onFailure(throwable -> task.complete(null));

        return task;
    }

    @Override
    public ITask<Void> updateUserWithoutClusterSyncAsync(IPermissionUser permissionUser) {
        Preconditions.checkNotNull(permissionUser);

        CompletableTask<Void> task = new NullCompletableTask<>();

        this.getDatabase().updateAsync(permissionUser.getUniqueId().toString(), new JsonDocument(permissionUser))
                .onComplete(success -> task.call())
                .onCancelled(booleanITask -> task.call())
                .onFailure(throwable -> task.call());

        return task;
    }

    @Override
    public ITask<Boolean> deleteUserWithoutClusterSyncAsync(IPermissionUser permissionUser) {
        Preconditions.checkNotNull(permissionUser);

        return this.getDatabase().deleteAsync(permissionUser.getUniqueId().toString());
    }

    @Override
    public boolean containsUser(@NotNull UUID uniqueId) {
        Preconditions.checkNotNull(uniqueId);

        return this.getDatabase().contains(uniqueId.toString());
    }

    @Override
    public @NotNull ITask<Boolean> containsUserAsync(@NotNull UUID uniqueId) {
        Preconditions.checkNotNull(uniqueId);

        return this.getDatabase().containsAsync(uniqueId.toString());
    }

    @Override
    public @NotNull ITask<Boolean> containsUserAsync(@NotNull String name) {
        Preconditions.checkNotNull(name);

        CompletableTask<Boolean> task = new CompletableTask<>();

        this.getUsersAsync(name)
                .onComplete(users -> task.complete(!users.isEmpty()))
                .onCancelled(listITask -> task.cancel(true))
                .onFailure(throwable -> task.complete(false));

        return task;
    }

    @Override
    public @NotNull ITask<IPermissionUser> getUserAsync(@NotNull UUID uniqueId) {
        Preconditions.checkNotNull(uniqueId);

        CompletableTask<IPermissionUser> task = new CompletableTask<>();

        this.getDatabase().getAsync(uniqueId.toString())
                .onComplete(document -> {
                    if (document == null) {
                        task.complete(null);
                        return;
                    }
                    IPermissionUser permissionUser = document.toInstanceOf(PermissionUser.TYPE);

                    if (this.testPermissionUser(permissionUser)) {
                        this.updateUser(permissionUser);
                    }

                    task.complete(permissionUser);
                })
                .onCancelled(listITask -> task.cancel(true))
                .onFailure(throwable -> task.complete(null));

        return task;
    }

    @Override
    public @NotNull ITask<List<IPermissionUser>> getUsersAsync(@NotNull String name) {
        Preconditions.checkNotNull(name);

        CompletableTask<List<IPermissionUser>> task = new CompletableTask<>();

        this.getDatabase().getAsync("name", name)
                .onComplete(documents -> task.complete(documents.stream().map(document -> {
                    IPermissionUser permissionUser = document.toInstanceOf(PermissionUser.TYPE);

                    if (this.testPermissionUser(permissionUser)) {
                        this.updateUser(permissionUser);
                    }

                    return permissionUser;
                }).collect(Collectors.toList())))
                .onCancelled(listITask -> task.cancel(true))
                .onFailure(throwable -> task.complete(Collections.emptyList()));

        return task;
    }

    @Override
    public @NotNull ITask<IPermissionUser> getFirstUserAsync(String name) {
        CompletableTask<IPermissionUser> task = new CompletableTask<>();

        this.getUsersAsync(name)
                .onComplete(users -> task.complete(users.isEmpty() ? null : users.get(0)))
                .onCancelled(listITask -> task.cancel(true))
                .onFailure(throwable -> task.complete(null));

        return task;
    }

    @Override
    public @NotNull ITask<Collection<IPermissionUser>> getUsersAsync() {
        Collection<IPermissionUser> permissionUsers = new ArrayList<>();
        ITask<Collection<IPermissionUser>> task = new ListenableTask<>(() -> permissionUsers);

        this.getDatabase().iterateAsync((key, document) -> {
            IPermissionUser permissionUser = document.toInstanceOf(PermissionUser.TYPE);
            this.testPermissionUser(permissionUser);

            permissionUsers.add(permissionUser);
        }).onComplete(aVoid -> {
            try {
                task.call();
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }).onCancelled(voidITask -> task.cancel(true)).onFailure(throwable -> {
            try {
                task.call();
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        });

        return task;
    }

    @Override
    public ITask<Void> setUsersWithoutClusterSyncAsync(Collection<? extends IPermissionUser> users) {
        Preconditions.checkNotNull(users);

        CompletableTask<Void> task = new NullCompletableTask<>();
        this.getDatabase().clearAsync().onComplete(aVoid -> {
            CountDownLatch latch = new CountDownLatch(users.size());

            for (IPermissionUser permissionUser : users) {
                if (permissionUser != null) {
                    this.getDatabase().insertAsync(permissionUser.getUniqueId().toString(), new JsonDocument(permissionUser))
                            .onComplete(success -> latch.countDown())
                            .onFailure(throwable -> latch.countDown())
                            .onCancelled(iTask -> latch.countDown());
                }
            }

            try {
                latch.await();
            } catch (InterruptedException exception) {
                exception.printStackTrace();
            }

            task.call();
        });
        return task;
    }

    @Override
    public @NotNull ITask<Collection<IPermissionUser>> getUsersByGroupAsync(@NotNull String group) {
        Preconditions.checkNotNull(group);

        Collection<IPermissionUser> permissionUsers = new ArrayList<>();
        ITask<Collection<IPermissionUser>> task = new ListenableTask<>(() -> permissionUsers);

        this.getDatabase().iterateAsync((key, document) -> {
            IPermissionUser permissionUser = document.toInstanceOf(PermissionUser.TYPE);

            this.testPermissionUser(permissionUser);
            if (permissionUser.inGroup(group)) {
                permissionUsers.add(permissionUser);
            }
        }).onComplete(aVoid -> {
            try {
                task.call();
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        });

        return task;
    }

    @Override
    public @NotNull ITask<IPermissionGroup> addGroupAsync(@NotNull String role, int potency) {
        return this.addGroupAsync(new PermissionGroup(role, potency));
    }

    @Override
    public @NotNull ITask<Boolean> containsGroupAsync(@NotNull String group) {
        Preconditions.checkNotNull(group);

        return CompletedTask.create(this.permissionGroupsMap.containsKey(group));
    }

    @Override
    public ITask<IPermissionGroup> addGroupWithoutClusterSyncAsync(IPermissionGroup permissionGroup) {
        Preconditions.checkNotNull(permissionGroup);

        this.permissionGroupsMap.put(permissionGroup.getName(), permissionGroup);
        this.saveGroups();

        return CompletedTask.create(permissionGroup);
    }

    @Override
    public ITask<Void> updateGroupWithoutClusterSyncAsync(IPermissionGroup permissionGroup) {
        Preconditions.checkNotNull(permissionGroup);

        this.permissionGroupsMap.put(permissionGroup.getName(), permissionGroup);

        this.saveGroups();

        return CompletedTask.voidTask();
    }

    @Override
    public ITask<Void> deleteGroupWithoutClusterSyncAsync(String group) {
        Preconditions.checkNotNull(group);

        IPermissionGroup permissionGroup = this.permissionGroupsMap.remove(group);
        if (permissionGroup != null) {
            this.saveGroups();
        }

        return CompletedTask.voidTask();
    }

    @Override
    public ITask<Void> deleteGroupWithoutClusterSyncAsync(IPermissionGroup group) {
        Preconditions.checkNotNull(group);

        return this.deleteGroupWithoutClusterSyncAsync(group.getName());
    }

    @Override
    public boolean containsGroup(@NotNull String name) {
        Preconditions.checkNotNull(name);

        return this.permissionGroupsMap.containsKey(name);
    }

    @Override
    public @NotNull ITask<IPermissionGroup> getGroupAsync(@NotNull String name) {
        Preconditions.checkNotNull(name);

        IPermissionGroup permissionGroup = this.permissionGroupsMap.get(name);

        if (this.testPermissionGroup(permissionGroup)) {
            ITask<IPermissionGroup> task = new ListenableTask<>(() -> permissionGroup);

            this.updateGroupAsync(permissionGroup).onComplete(aVoid -> {
                try {
                    task.call();
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            });

            return task;
        } else {
            return CompletedTask.create(permissionGroup);
        }
    }

    @Override
    public ITask<IPermissionGroup> getDefaultPermissionGroupAsync() {
        for (IPermissionGroup group : this.permissionGroupsMap.values()) {
            if (group != null && group.isDefaultGroup()) {
                return CompletedTask.create(group);
            }
        }

        return CompletedTask.create(null);
    }

    @Override
    public @NotNull ITask<Collection<IPermissionGroup>> getGroupsAsync() {
        CountingTask<Collection<IPermissionGroup>> task = new CountingTask<>(this.permissionGroupsMap.values(), this.permissionGroupsMap.size());

        for (IPermissionGroup permissionGroup : this.permissionGroupsMap.values()) {
            if (this.testPermissionGroup(permissionGroup)) {
                this.updateGroupAsync(permissionGroup)
                        .onComplete(aVoid -> task.countDown())
                        .onCancelled(voidITask -> task.countDown())
                        .onFailure(throwable -> task.countDown());
            } else {
                task.countDown();
            }
        }

        return task;
    }

    @Override
    public Collection<IPermissionGroup> getGroups() {
        for (IPermissionGroup permissionGroup : this.permissionGroupsMap.values()) {
            if (this.testPermissionGroup(permissionGroup)) {
                this.updateGroup(permissionGroup);
            }
        }

        return this.permissionGroupsMap.values();
    }

    @Override
    public @NotNull ITask<Collection<IPermissionGroup>> getGroupsAsync(@Nullable IPermissionUser permissionUser) {
        return CompletedTask.create(
                permissionUser.getGroups().stream()
                        .map(PermissionUserGroupInfo::getGroup)
                        .map(this::getGroup)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList())
        );
    }

    @Override
    public @NotNull ITask<IPermissionUser> addUserAsync(@NotNull String name, @NotNull String password, int potency) {
        return this.addUserAsync(new PermissionUser(UUID.randomUUID(), name, password, potency));
    }

    @Override
    public ITask<Void> setGroupsWithoutClusterSyncAsync(Collection<? extends IPermissionGroup> groups) {
        Preconditions.checkNotNull(groups);

        this.permissionGroupsMap.clear();

        for (IPermissionGroup group : groups) {
            this.testPermissionGroup(group);
            this.permissionGroupsMap.put(group.getName(), group);
        }

        this.saveGroups();

        return CompletedTask.voidTask();
    }

    @Override
    public boolean reload() {
        loadGroups();

        if (this.permissionManagementHandler != null) {
            this.permissionManagementHandler.handleReloaded(this);
        }

        return true;
    }

    private void saveGroups() {
        List<IPermissionGroup> permissionGroups = new ArrayList<>(this.permissionGroupsMap.values());
        Collections.sort(permissionGroups);

        new JsonDocument("groups", permissionGroups).write(this.file);
    }

    private void loadGroups() {
        JsonDocument document = JsonDocument.newDocument(this.file);

        if (document.contains("groups")) {
            Collection<PermissionGroup> permissionGroups = document.get("groups", new TypeToken<Collection<PermissionGroup>>() {
            }.getType());

            this.permissionGroupsMap.clear();

            for (PermissionGroup group : permissionGroups) {
                this.permissionGroupsMap.put(group.getName(), group);
            }

            // saving the groups again to be sure that new fields in the permission group are in the file too
            document.append("groups", this.permissionGroupsMap.values());
            document.write(this.file);
        }
    }

    public IDatabase getDatabase() {
        return this.getDatabaseProvider().getDatabase(DATABASE_USERS_NAME);
    }

    private AbstractDatabaseProvider getDatabaseProvider() {
        try {
            return this.databaseProviderCallable.call();
        } catch (Exception exception) {
            throw new Error("An error occurred while attempting to get the database provider", exception);
        }
    }

    public Map<String, IPermissionGroup> getPermissionGroupsMap() {
        return this.permissionGroupsMap;
    }

    public Callable<AbstractDatabaseProvider> getDatabaseProviderCallable() {
        return this.databaseProviderCallable;
    }

    public IPermissionManagementHandler getPermissionManagementHandler() {
        return this.permissionManagementHandler;
    }

    public void setPermissionManagementHandler(IPermissionManagementHandler permissionManagementHandler) {
        this.permissionManagementHandler = permissionManagementHandler;
    }
}
