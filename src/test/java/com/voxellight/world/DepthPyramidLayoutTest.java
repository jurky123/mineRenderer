package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DepthPyramidLayoutTest {
    @Test void allocationCountsRealNativeMipSizesAndBoundsMemory() {
        for(int[] size:new int[][]{{1,1},{1,17},{17,1},{853,479},{2560,1440},{3840,2160}}) {
            var layout=DepthPyramidLayout.of(size[0],size[1]);long bytes=0;
            for(int i=0;i<layout.levels();i++)bytes+=4L*layout.width(i)*layout.height(i);
            assertEquals(bytes,layout.bytes());assertEquals(1,Math.min(layout.width(layout.levels()-1),layout.height(layout.levels()-1)));
            assertTrue(bytes<=DepthPyramidLayout.LIMIT);
        }
        assertThrows(IllegalArgumentException.class,()->DepthPyramidLayout.of(0,12));
    }
    @Test void rectangularMipViewsNeverShiftEitherNativeDimensionToZero(){
        for(int[] size:new int[][]{{2560,1352},{1,17},{17,1},{3840,1080},{1080,3840}}){
            var layout=DepthPyramidLayout.of(size[0],size[1]);
            for(int level=0;level<layout.levels();level++){assertTrue((layout.width()>>level)>0);assertTrue((layout.height()>>level)>0);}
        }
    }
    @Test void everyOddTailAndSinglePixelOccluderSurvivesAllReductionLevels() {
        for(int[] size:new int[][]{{1,1},{1,17},{17,1},{7,13},{13,7},{19,23}}) {
            int w=size[0],h=size[1];var layout=DepthPyramidLayout.of(w,h);
            for(int py=0;py<h;py++)for(int px=0;px<w;px++) {
                double[][] source=new double[h][w];source[py][px]=.875;
                for(int level=0;level<layout.levels();level++) {
                    source=reduce(source,level==0,layout.width(level),layout.height(level));
                    int span=1<<(level+1);
                    // Independent full-resolution footprint oracle; last cells absorb odd tails.
                    for(int y=0;y<source.length;y++)for(int x=0;x<source[0].length;x++) {
                        int endX=x==source[0].length-1?w:(x+1)*span,endY=y==source.length-1?h:(y+1)*span;
                        double expected=px>=x*span && px<endX && py>=y*span && py<endY?.875:0;
                        assertEquals(expected,source[y][x]);
                    }
                }
                double maximum=0;for(var row:source)for(double value:row)maximum=Math.max(maximum,value);assertEquals(.875,maximum);
            }
        }
    }
    @Test void maximumReversedDepthRejectsOnlyEntirelyFrontSegments() {
        double[][] source={{.2,.7,0},{.1,.6,.9},{0,0,0}};
        var first=reduce(source,true,2,2);assertEquals(.7,first[0][0]);assertEquals(.9,first[0][1]);assertEquals(0,first[1][0]);
        var root=reduce(first,false,1,1);assertEquals(.9,root[0][0]);
        for(double[] segment:new double[][]{{.95,.92},{.8,.95},{.95,.2},{.2,.3}}) {
            boolean skip=Math.min(segment[0],segment[1])>root[0][0];
            if(skip)for(var row:source)for(var depth:row)assertTrue(segment[0]>depth && segment[1]>depth);
        }
    }
    @Test void perspectiveBoundarySolveMatchesProjectedCellEdge() {
        for(double originW:new double[]{1,5,30})for(double rayW:new double[]{-.1,.3,1})for(double edge:new double[]{-.8,-.2,.4,.9}) {
            double originX=-.9*originW,rayX=1.7,denominator=rayX-edge*rayW;
            double t=(edge*originW-originX)/denominator;
            assertEquals(edge,(originX+rayX*t)/(originW+rayW*t),1e-12);
        }
    }
    private static double[][] reduce(double[][] input,boolean first,int w,int h) {
        double[][] output=new double[h][w];int sh=input.length,sw=input[0].length;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int endX=!first && x==w-1?sw:Math.min(sw,2*x+2),endY=!first && y==h-1?sh:Math.min(sh,2*y+2);
            for(int py=2*y;py<endY;py++)for(int px=2*x;px<endX;px++)output[y][x]=Math.max(output[y][x],input[py][px]);
        }
        return output;
    }
}
