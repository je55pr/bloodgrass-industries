package io.github.je55pr.bloodgrassindustries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;

final class QuarryMaterialsScreen extends Screen {
    private enum View { OUTPUT, ROAD, WALL, LAST_CUT }

    private static final int ROW_HEIGHT = 15;
    private final QuarryScreen quarry;
    private View view = View.OUTPUT;
    private QuarryMaterials.Report previousReport;
    private QuarryMaterials.Finish previousFinish;
    private List<Row> rows = List.of();
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int visibleRows;
    private int offset;
    private boolean blockedFallingChoices;
    private Button survey;

    QuarryMaterialsScreen(QuarryScreen quarry) {
        super(Component.literal("Quarry materials"));
        this.quarry = quarry;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(366, width - 16);
        panelHeight = Math.min(272, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listTop = top + 70;
        visibleRows = Math.max(1, (panelHeight - 127) / ROW_HEIGHT);
        int tabWidth = (panelWidth - 23) / 4;
        tab("Output", 0, tabWidth, View.OUTPUT);
        tab("Road", 1, tabWidth, View.ROAD);
        tab("Wall", 2, tabWidth, View.WALL);
        tab("Last cut", 3, tabWidth, View.LAST_CUT);
        survey = addRenderableWidget(Button.builder(Component.literal("Survey materials"), ignored -> quarry.surveyMaterials())
                .bounds(left + 9, top + panelHeight - 26, 113, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(left + panelWidth - 64, top + panelHeight - 26, 55, 18).build());
        rebuild();
    }

    private void tab(String label, int index, int width, View next) {
        addRenderableWidget(Button.builder(Component.literal(label), ignored -> {
            view = next;
            rebuild();
        }).bounds(left + 9 + index * (width + 2), top + 24, width, 18).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(quarry);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void rebuild() {
        previousReport = quarry.report();
        previousFinish = quarry.finish();
        offset = 0;
        blockedFallingChoices = false;
        rows = switch (view) {
            case LAST_CUT -> outputRows(quarry.getMenu().lastOutput);
            case OUTPUT -> estimatedOutputRows();
            case ROAD, WALL -> finishRows(view == View.WALL);
        };
    }

    private List<Row> estimatedOutputRows() {
        if (previousReport == null) {
            return List.of();
        }
        QuarryMaterials.Allocation allocation = quarry.allocation();
        return outputRows(allocation == null ? previousReport.materials().output() : allocation.output());
    }

    private List<Row> finishRows(boolean wall) {
        List<Row> choices = new ArrayList<>();
        choices.add(new Row(Component.literal("Natural"), "", Blocks.AIR));
        if (previousReport == null || previousReport.surfaces().cost(wall, Blocks.AIR) == 0) {
            return choices;
        }
        Map<Block, Long> candidates = previousReport.materials().candidates();
        blockedFallingChoices = previousReport.surfaces().cannotSupport(wall)
                && candidates.keySet().stream().anyMatch(FallingBlock.class::isInstance);
        candidates.entrySet().stream()
                .filter(entry -> entry.getValue() >= previousReport.surfaces().cost(wall, entry.getKey())
                        && previousReport.allocate(with(entry.getKey())) != null)
                .sorted(Map.Entry.<Block, Long>comparingByValue().reversed().thenComparing(entry -> QuarryMaterials.blockId(entry.getKey())))
                .forEach(entry -> choices.add(new Row(entry.getKey().getName(), Long.toString(entry.getValue()), entry.getKey())));
        return choices;
    }

    private static List<Row> outputRows(Map<Item, Long> items) {
        return items.entrySet().stream().sorted(Map.Entry.<Item, Long>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().getDescriptionId()))
                .map(entry -> new Row(entry.getKey().getDescription(), Long.toString(entry.getValue()), null)).toList();
    }

    private QuarryMaterials.Finish with(Block block) {
        return view == View.ROAD ? new QuarryMaterials.Finish(block, quarry.finish().wall())
                : new QuarryMaterials.Finish(quarry.finish().road(), block);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (previousReport != quarry.report() || !quarry.finish().equals(previousFinish)) {
            rebuild();
        }
        survey.active = quarry.plan() != null && minecraft.player.isCreative();
        super.renderBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xff24262a);
        graphics.drawString(font, title, left + 9, top + 9, 0xffeeeeee);
        graphics.drawString(font, heading(), left + 9, top + 50, 0xffefbf80);
        if (rows.size() > visibleRows) {
            String range = (offset + 1) + "–" + (offset + visibleRows) + " of " + rows.size();
            graphics.drawString(font, range, left + panelWidth - 9 - font.width(range), top + 60, 0xffc2c7cd);
        }
        if (previousReport != null && (view == View.ROAD || view == View.WALL)) {
            Block selected = view == View.ROAD ? quarry.finish().road() : quarry.finish().wall();
            graphics.drawString(font, Component.literal("Selected ").append(selected == Blocks.AIR ? Component.literal("Natural") : selected.getName()),
                    left + 9, top + 60, 0xffeeeeee);
        }
        graphics.fill(left + 9, listTop, left + panelWidth - 9, listTop + visibleRows * ROW_HEIGHT, 0xff16191e);
        graphics.enableScissor(left + 9, listTop, left + panelWidth - 9, listTop + visibleRows * ROW_HEIGHT);
        for (int i = 0; i < visibleRows && offset + i < rows.size(); i++) {
            Row row = rows.get(offset + i);
            int y = listTop + i * ROW_HEIGHT;
            if (mouseX >= left + 9 && mouseX < left + panelWidth - 9 && mouseY >= y && mouseY < y + ROW_HEIGHT && row.block() != null) {
                graphics.fill(left + 9, y, left + panelWidth - 9, y + ROW_HEIGHT, 0xff3e4249);
            }
            graphics.drawString(font, row.label(), left + 15, y + 4, 0xffeeeeee);
            graphics.drawString(font, row.count(), left + panelWidth - 16 - font.width(row.count()), y + 4, 0xffeeeeee);
        }
        graphics.disableScissor();
        graphics.drawWordWrap(font, status(), left + 9, listTop + visibleRows * ROW_HEIGHT + 4, panelWidth - 18, 0xffc2c7cd);
    }

    private String heading() {
        return switch (view) {
            case OUTPUT -> previousReport != null && quarry.allocation() == null
                    ? "Mining output before finishes" : "Expected mining output after finishes";
            case LAST_CUT -> "Last excavation output";
            case ROAD -> previousReport == null ? "Road finish" : "Road finish · " + previousReport.surfaces().cost(false, Blocks.AIR) + " surface blocks";
            case WALL -> previousReport == null ? "Wall finish" : "Walls and edges · " + previousReport.surfaces().cost(true, Blocks.AIR) + " surface blocks";
        };
    }

    private Component status() {
        if (view == View.LAST_CUT && !quarry.getMenu().hasLastOutput) {
            return Component.literal("Complete an excavation to see its output totals.");
        }
        if (view == View.LAST_CUT) {
            return Component.literal(rows.size() > visibleRows ? "Scroll for more." : "");
        }
        String status = quarry.materialStatus();
        if (!status.isEmpty()) {
            return Component.literal(status);
        }
        if (previousReport == null) {
            return Component.literal("Survey the outline to calculate drops and finish materials.");
        }
        if (previousReport.surfaces().cannotSupport(quarry.finish()) || blockedFallingChoices) {
            return Component.literal(QuarryMaterials.NO_SUPPORT_SPACE);
        }
        QuarryMaterials.Allocation allocation = quarry.allocation();
        if (allocation == null) {
            return Component.literal("The cut cannot supply the selected finishes together.");
        }
        if (allocation.supportCount() != 0) {
            return Component.literal("Falling blocks need " + allocation.supportCount() + " support blocks of ")
                    .append(allocation.support().getName());
        }
        return Component.literal(rows.size() > visibleRows ? "Scroll for more." : "");
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && x >= left + 9 && x < left + panelWidth - 9 && y >= listTop && y < listTop + visibleRows * ROW_HEIGHT) {
            int index = offset + (int) (y - listTop) / ROW_HEIGHT;
            if (index < rows.size() && rows.get(index).block() != null) {
                quarry.setFinish(with(rows.get(index).block()));
                rebuild();
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (rows.size() > visibleRows && vertical != 0) {
            offset = Math.clamp((long) offset + (vertical > 0 ? -1 : 1), 0, Math.max(0, rows.size() - visibleRows));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private record Row(Component label, String count, Block block) {}
}
