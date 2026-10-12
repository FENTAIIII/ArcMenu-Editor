package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketLayoutTest {
    @Test void storefrontCardsRemainInsideSmallAndLargeViewports() {
        for (int width : new int[]{360, 480, 640, 960, 1280}) {
            var layout = MarketLayout.grid(width, 400);
            for (int column = 0; column < layout.columns(); column++) {
                var card = layout.card(column, 0);
                assertTrue(card.x() >= 0); assertTrue(card.right() <= width - 12);
                assertTrue(card.width() >= 120); assertEquals(column, layout.hit(card.x() + 8, card.y() + 8, 20, 0));
            }
        }
    }
    @Test void guttersAndScrolledOffscreenCardsCannotActivateListings() {
        var layout = MarketLayout.grid(960, 540); var first = layout.card(0, 0);
        assertEquals(-1, layout.hit(first.right() + 2, first.y() + 8, 20, 0));
        int scroll = layout.cardHeight() + MarketLayout.GAP;
        assertEquals(layout.columns(), layout.hit(layout.left() + 8, layout.top() + 8, 20, scroll));
        assertEquals(-1, layout.hit(layout.left() + 8, layout.top() - 1, 20, scroll));
    }
    @Test void paginationScrollBoundMatchesTheLastRenderedCard() {
        var layout = MarketLayout.grid(640, 400);
        assertEquals(0, layout.maximumScroll(0));
        var last = layout.card(19, layout.maximumScroll(20));
        assertEquals(layout.top() + layout.height(), last.bottom());
    }
    @Test void automaticGuiScaleKeepsCompleteCardsVisibleInShortWindows() {
        for (int[] size : new int[][]{{320,240},{480,270},{640,300}}) {
            var layout=MarketLayout.grid(size[0],size[1]);
            assertEquals(0,MarketLayout.sidebar(size[0],size[1]));
            assertTrue(layout.cardHeight()<=layout.height());
            assertTrue(layout.cardHeight()>=100);
            assertTrue(layout.card(0,0).bottom()<=size[1]-MarketLayout.FOOTER);
        }
    }
}
