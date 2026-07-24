package bid.yuanlu.seedmap4xaero.client.accessor;

public interface SeedMapToggleAccessor {
    /**
     * 标识种子地图当前是否启用
     * @return 配置文件启用 && loadedWorldInfo
     */
    boolean xsm$isSeedMapEnabled();

    void xsm$setSeedMapEnabled(boolean enabled);

    /**
     * 标识种子地图当前是否已经正确加载世界信息(seed,dim)
     */
    void xsm$setSeedMapLoadedWorldInfo(boolean loaded);
}
