package rh.maparthelper.conversion.schematic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.Shapes;
import rh.maparthelper.MapartHelper;
import rh.maparthelper.colors.MapColorEntry;
import rh.maparthelper.config.UseAuxBlocks;
import rh.maparthelper.conversion.staircases.StaircaseStyles;
import rh.maparthelper.palette.PaletteColors;
import rh.maparthelper.palette.PaletteDataManager;
import rh.maparthelper.palette.PaletteGenerator;
import rh.maparthelper.palette.RegisteredPalettePreset;

import java.util.ArrayList;
import java.util.List;

public class MapartSchematicBuilder {
    private final RegisteredPalettePreset preset;

    private final CompoundTag nbt = new CompoundTag();
    private final ListTag blocks = new ListTag();
    private final int[][] mapColors;
    private final int xSize;
    private int ySize;
    private int minY = 0;
    private final int zSize;
    private final int lowestBlockLayer; // 0 as default, 1 for placing aux blocks at the platform layer height
    private final List<Block> blocks_palette = new ArrayList<>();
    private final boolean[] prevRowSupportBlock;
    private final List<List<Integer>> heightsZX;

    private final StaircaseStyles staircaseStyle = MapartHelper.conversionConfig().getStaircaseStyle();
    private final UseAuxBlocks useAuxBlocks = MapartHelper.conversionConfig().getUseAuxBlocks();
    private final Block auxBlock = MapartHelper.conversionConfig().getAuxBlock();

    public MapartSchematicBuilder(int[] mapColors, int mapsWidth, int mapsHeight, boolean addPlatformLayerAuxBlocks) {
        this.preset = PaletteDataManager.getInstance().getPresetsHandler().getSelectedPreset();
        this.xSize = mapsWidth * 128;
        this.zSize = useAuxBlocks == UseAuxBlocks.TRUST_ME ? mapsHeight * 128 : mapsHeight * 128 + 1;
        this.prevRowSupportBlock = new boolean[xSize];
        this.mapColors = useAuxBlocks == UseAuxBlocks.TRUST_ME ? new int[zSize][xSize] : new int[zSize - 1][xSize];
        int colorsHeight = useAuxBlocks == UseAuxBlocks.TRUST_ME ? zSize : zSize - 1;
        for (int z = 0; z < colorsHeight; z++) {
            System.arraycopy(mapColors, z * xSize, this.mapColors[z], 0, xSize);
        }
        if (staircaseStyle != StaircaseStyles.FLAT_2D)
            heightsZX = staircaseStyle.getStaircase(this.mapColors);
        else
            heightsZX = null;
        this.lowestBlockLayer = addPlatformLayerAuxBlocks ? 1 : 0;
        ySize = lowestBlockLayer + 1;
        addBaseMetadata();
    }

    public CompoundTag build() {
        for (int z = 0; z < zSize; z++) {
            for (int x = 0; x < xSize; x++) {
                int y = getBlockHeight(x, z);

                if (useAuxBlocks == UseAuxBlocks.TRUST_ME) {
                    MapColorEntry entry = PaletteColors.getMapColorEntryByARGB(mapColors[z][x]);

                    if (entry.mapColor() != MapColor.NONE) {
                        addColor(entry, x, y, z);
                    }
                } else if (z == 0) {
                    addBlock(auxBlock, x, y, z);
                } else {
                    MapColorEntry entry = PaletteColors.getMapColorEntryByARGB(mapColors[z - 1][x]);

                    if (entry.mapColor() != MapColor.NONE) {
                        addColor(entry, x, y, z);
                    }
                }
            }
        }

        normalizeYCoordinates();
        addSize();
        addPaletteAndBlocks();
        return nbt;
    }

    private void normalizeYCoordinates() {
        if (minY >= 0) {
            return;
        }

        int offset = -minY;

        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i).orElseThrow();

            ListTag pos = entry.getList("pos").orElseThrow();

            int y = pos.getInt(1).orElseThrow();

            pos.set(1, IntTag.valueOf(y + offset));
            entry.put("pos", pos);
        }

        ySize += offset;
        minY = 0;
    }

    private int getBlockMaterialId(Block block) {
        int ind = blocks_palette.indexOf(block);
        if (ind != -1) return ind;
        blocks_palette.add(block);
        return blocks_palette.size() - 1;
    }

    private void addColor(MapColorEntry entry, int x, int y, int z) {
        MapColor mapColor = entry.mapColor();
        Block block = preset.getBlockOfMapColor(mapColor);

        if (mapColor == MapColor.WATER) {
            int leafCount = getWaterLeafCount(entry);

            for (int i = 0; i < leafCount; i++) {
                addBlock(block, x, y - i, z);
            }

            // solid block must be below water
            addBlock(auxBlock, x, y - leafCount, z);
        } else {
            addBlock(block, x, y, z);
        }

        if (useAuxBlocks != UseAuxBlocks.NO_AUX) {
            addAuxBlocksForColor(block, x, y, z);
        }
    }

    private int getWaterLeafCount(MapColorEntry entry) {
        // water shades are determined by depth
        return switch (entry.brightness()) {
            case LOW, LOWEST -> 10;
            case NORMAL -> 5;
            case HIGH -> 1;
        };
    }

    private void addAuxBlocksForColor(Block colorBlock, int colorX, int colorY, int colorZ) {
        if (useAuxBlocks == UseAuxBlocks.TRUST_ME) return;

        boolean needsSupport = shouldPlaceAuxBlock(colorBlock);
        if (colorY > 0 && needsSupport)
            addBlock(auxBlock, colorX, colorY - 1, colorZ);

        if (useAuxBlocks == UseAuxBlocks.ALL && staircaseStyle != StaircaseStyles.FLAT_2D) {
            int yDiff = getBlockHeight(colorX, colorZ - 1) - colorY;
            if (yDiff == 1) {
                if (needsSupport && colorY > 0)
                    addBlock(auxBlock, colorX, colorY - 1, colorZ - 1);
                if (!prevRowSupportBlock[colorX])
                    addBlock(auxBlock, colorX, colorY, colorZ - 1);
            } else if (yDiff == -1) {
                if (!needsSupport && colorY > lowestBlockLayer)
                    addBlock(auxBlock, colorX, colorY - 1, colorZ);
                if (prevRowSupportBlock[colorX] && colorY > 1)
                    addBlock(auxBlock, colorX, colorY - 2, colorZ);
            } else if (yDiff == 0 && colorY > lowestBlockLayer) {
                if (needsSupport) {
                    if (!prevRowSupportBlock[colorX] && (colorZ == 1 || getBlockHeight(colorX, colorZ - 2) - colorY != -1))
                        addBlock(auxBlock, colorX, colorY - 1, colorZ - 1);
                } else if (prevRowSupportBlock[colorX])
                    addBlock(auxBlock, colorX, colorY - 1, colorZ);
            }
            prevRowSupportBlock[colorX] = needsSupport;
        }
    }

    private void addBlock(Block block, int x, int y, int z) {
        CompoundTag entry = new CompoundTag();

        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));

        entry.put("pos", pos);
        int blockId = getBlockMaterialId(block);
        entry.put("state", IntTag.valueOf(blockId));

        blocks.add(entry);

        minY = Math.min(minY, y);
        ySize = Math.max(ySize, y + 1);
    }

    private void addPaletteAndBlocks() {
        ListTag palette = new ListTag();
        for (Block block : blocks_palette) {
            BlockState blockState = PaletteGenerator.getDefaultPaletteState(block);

            if(block == Blocks.BEACON){
                blockState = PaletteGenerator.getDefaultPaletteState(Blocks.OAK_LEAVES).trySetValue(BlockStateProperties.WATERLOGGED, true);
            }

            CompoundTag blockEntry = NbtUtils.writeBlockState(blockState);
            palette.add(blockEntry);
        }
        nbt.put("palette", palette);
        nbt.put("blocks", blocks);
    }

    private void addSize() {
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(xSize));
        size.add(IntTag.valueOf(ySize));
        size.add(IntTag.valueOf(zSize));
        nbt.put("size", size);
    }

    private void addBaseMetadata() {
        String author = Minecraft.getInstance().getUser().getName() + " // using Mapart Helper";
        nbt.putString("author", author);
        NbtUtils.addCurrentDataVersion(nbt);
    }

    private int getBlockHeight(int x, int z) {
        if (staircaseStyle == StaircaseStyles.FLAT_2D) return lowestBlockLayer;
        return heightsZX.get(z).get(x) + lowestBlockLayer;
    }

    public static boolean shouldPlaceAuxBlock(Block block) {
        if (MapartHelper.conversionConfig().getUseAuxBlocks() == UseAuxBlocks.NO_AUX || block.defaultBlockState().isAir())
            return false;
        return needsAuxBlock(block);
    }

    public static boolean needsAuxBlock(Block block) {
        boolean canPlaceAtAir = block.defaultBlockState().canSurvive(DummyLevelView.getInstance(), BlockPos.ZERO);
        boolean hasNoCollision = block.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == Shapes.empty();
        return !canPlaceAtAir || block instanceof FallingBlock || block instanceof SlabBlock || hasNoCollision;
    }
}
