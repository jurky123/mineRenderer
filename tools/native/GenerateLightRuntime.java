import com.voxellight.rt.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
class GenerateLightRuntime {
    public static void main(String[] args)throws Exception{
        var points=new ArrayList<RtEmitterTable.Triangle>();
        for(int i=0;i<16;i++)points.add(new RtEmitterTable.Triangle(i,0,new float[]{0,0,i+1,(i+1)*7,(16-i)*11,i%2==1?17:3,0,0,0},0,100,1));
        var mixed=new ArrayList<>(points);
        mixed.add(new RtEmitterTable.Triangle(32,0,new float[]{-1,-1,3,1,-1,3,-1,1,3},2,12,0));
        mixed.add(new RtEmitterTable.Triangle(33,0,new float[]{1,-1,3,1,1,3,-1,1,3},2,12,0));
        for(String mode:List.of("points","mixed","adapted","empty")){
            float[] factors={1,1,1,1,1,1,1,1};if(mode.equals("adapted"))factors=new float[]{.25f,.4f,.6f,.3f,.9f,.7f,.8f,1};
            var runtime=RtLightRuntime.build(mode.equals("empty")?List.of():mode.equals("points")?points:mixed,0,0,0,factors);
            byte[] bytes=new byte[runtime.remaining()];runtime.get(bytes);Files.write(Path.of(args[0],mode+".light-runtime.bin"),bytes);
        }
    }
}
