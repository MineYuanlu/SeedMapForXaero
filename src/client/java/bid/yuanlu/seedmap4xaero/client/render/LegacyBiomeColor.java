package bid.yuanlu.seedmap4xaero.client.render;

public final class LegacyBiomeColor implements BiomeColorProvider {

    public static final LegacyBiomeColor INSTANCE = new LegacyBiomeColor();

    private LegacyBiomeColor() {
    }

    private static final int[] TABLE = new int[256];
    private static final boolean[] AQUATIC = new boolean[256];
    private static final int UNKNOWN_COLOR = 0x555555;

    static {
        initColors();
        initAquatic();
    }

    @Override
    public String name() {
        return "Legacy (Hardcoded)";
    }

    @Override
    public String translationKey() {
        return "xsm.color_provider.legacy_hardcoded";
    }

    @Override
    public int getColor(int biomeId, int blockX, int blockZ) {
        if (biomeId >= 0 && biomeId < TABLE.length) {
            return TABLE[biomeId];
        }
        return UNKNOWN_COLOR;
    }

    @Override
    public boolean isAquatic(int biomeId) {
        return biomeId >= 0 && biomeId < AQUATIC.length && AQUATIC[biomeId];
    }

    private static void initColors() {
        set(0, 0x000070);
        set(24, 0x000040);
        set(44, 0x004080);
        set(45, 0x006080);
        set(46, 0x003070);
        set(47, 0x002050);
        set(48, 0x004060);
        set(49, 0x001040);
        set(50, 0x202540);
        set(10, 0xA0C4D6);

        set(7, 0x0000FF);
        set(11, 0xA0CBFF);

        set(1, 0x91BD59);
        set(129, 0xA5C962);
        set(177, 0x7FA86F);

        set(4, 0x4E6E38);
        set(27, 0x6BAA5E);
        set(28, 0x6E9E60);
        set(29, 0x3E5840);
        set(155, 0x5C8E52);
        set(156, 0x55844B);
        set(132, 0x699E48);
        set(157, 0x385C3A);

        set(5, 0x2B5E38);
        set(30, 0x628268);
        set(31, 0x58745E);
        set(32, 0x3A5C3C);
        set(33, 0x3D5A3F);
        set(160, 0x445E44);
        set(161, 0x405A40);
        set(133, 0x2A5032);
        set(158, 0x48604E);

        set(3, 0x6B6B6B);
        set(131, 0x868686);
        set(34, 0x5A6B5A);
        set(162, 0x7A7A7A);
        set(20, 0x707070);
        set(140, 0xDFDFDF);

        set(17, 0xCABF74);
        set(18, 0x486828);
        set(19, 0x2A4E32);
        set(22, 0x285828);

        set(2, 0xFAD98F);
        set(130, 0xEFC274);

        set(35, 0xBDA565);
        set(36, 0xA58C52);
        set(163, 0x9E8046);
        set(164, 0x8A7340);

        set(37, 0xD67B34);
        set(38, 0xB7523A);
        set(39, 0xDB7437);
        set(165, 0xC76B2E);
        set(166, 0x9E3E2B);
        set(167, 0xC96430);

        set(6, 0x4B6B3A);
        set(134, 0x3F5E2E);
        set(184, 0x5C7346);

        set(21, 0x2C6E18);
        set(23, 0x3A7D28);
        set(149, 0x247010);
        set(151, 0x366E26);
        set(168, 0x339E2E);
        set(169, 0x2E8C2A);

        set(12, 0xF0F4F8);
        set(13, 0xDBE4EC);
        set(179, 0xEBF0F4);
        set(180, 0xDDE5EB);
        set(181, 0xD8E0E8);
        set(26, 0xD2DDE8);
        set(178, 0xD0E0D8);

        set(16, 0xE8E09C);
        set(25, 0x7A7A7A);

        set(14, 0x8C788C);
        set(15, 0xA08EA0);

        set(8, 0x7A2A1F);
        set(170, 0x58433F);
        set(171, 0x8A2020);
        set(172, 0x3A4B4B);
        set(173, 0x4A4848);

        set(9, 0xC9C992);
        set(40, 0xBDBD7A);
        set(41, 0xBFBF80);
        set(42, 0xB8B870);
        set(43, 0xC4C488);

        set(174, 0x5E5A50);
        set(175, 0x506844);
        set(182, 0x8A8680);
        set(183, 0x1A1A22);
        set(187, 0x8B8C60);

        set(185, 0xFFC0CB);
        set(186, 0x6B6B55);

        set(51, 0x588A38);
        set(52, 0x247010);
        set(53, 0x6E885A);

        set(127, 0x000000);

        for (int i = 0; i < TABLE.length; i++) {
            if (TABLE[i] == 0) {
                TABLE[i] = UNKNOWN_COLOR;
            }
        }
    }

    private static void initAquatic() {
        int[] aquaticIds = {
                0, 7, 10, 11, 24, 44, 45, 46, 47, 48, 49, 50
        };
        for (int id : aquaticIds) {
            AQUATIC[id] = true;
        }
    }

    private static void set(int biomeId, int rgb) {
        if (biomeId >= 0 && biomeId < TABLE.length) {
            TABLE[biomeId] = rgb;
        }
    }
}
