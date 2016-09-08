package wrapper.toupcam;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JLabel;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import wrapper.toupcam.callbacks.EventCallback;
import wrapper.toupcam.callbacks.ImageStreamCallback;
import wrapper.toupcam.callbacks.PTOUPCAM_DATA_CALLBACK;
import wrapper.toupcam.callbacks.PTOUPCAM_HOTPLUG_CALLBACK;
import wrapper.toupcam.enumerations.Event;
import wrapper.toupcam.enumerations.HResult;
import wrapper.toupcam.enumerations.Options;
import wrapper.toupcam.exceptions.StreamingException;
import wrapper.toupcam.libraries.LibToupcam;
import wrapper.toupcam.models.Image;
import wrapper.toupcam.models.ImageHeader;
import wrapper.toupcam.models.Model;
import wrapper.toupcam.models.RawFormat;
import wrapper.toupcam.models.Resolution;
import wrapper.toupcam.models.ToupcamInst;
import wrapper.toupcam.util.Constants;
import wrapper.toupcam.util.NativeUtils;
import wrapper.toupcam.util.ParserUtil;
import wrapper.toupcam.util.Util;

public class App implements Toupcam  {

	private LibToupcam libToupcam = null;
	private Pointer camHandler;
	private JFrame jFrame;

	private boolean isStreaming = false;
	
	// cache variable to store callback for image, for use case when streaming
	// has to be stopped and restarted.
	private ImageStreamCallback imageCallback = null;	 

	public static void main(String[] args){
		App app = new App();
		Native.setProtected(true);
		//List<ToupcamInst> cams = app.getToupcams();	// some pointer issue in windows
		//System.out.println(cams);		

		int camsConnected = app.countConnectedCams();
		if(camsConnected == 0){
			System.out.println("No Toupcams detected");
			System.exit(-1);
		}

		//app.registerPlugInOrOut(); 		// not available in windows
		app.camHandler = app.openCam(null);
		Util.keepVMRunning();

		System.out.println("Set Resolution Result: " + app.setResolution(app.camHandler, 2));
		//System.out.println("Set RAW Options Result: " + app.setOptions(handler, Options.OPTION_RAW, 1));

		ImageStreamCallback imageCallback = new ImageStreamCallback(){
			@Override public void onReceivePreviewImage(BufferedImage image, ImageHeader imageHeader) {					
				Native.setProtected(true);
				System.out.println(imageHeader);
			}

			@Override public void onReceiveStillImage(BufferedImage image, ImageHeader imageHeader) {}
		};
		
		app.startStreaming(imageCallback);
		
		/*try{
			Thread.sleep(4000);
			app.pauseStreaming();
			System.out.println("----- Image Streaming Paused -----");
			Thread.sleep(2000);
			System.out.println("----- Resuming Image Stream -----");
			app.resumeStreaming();
		}catch(Exception e){System.out.println(e);}*/

		
		try{
			Thread.sleep(4000);
			app.stopStreaming();
			System.out.println("----- Image Streaming Stopped -----");
			Thread.sleep(2000);
			app.startStreaming(imageCallback);
			System.out.println("----- Image Streaming Restarted -----");
		}catch(Exception e){System.out.println(e);}

		//app.startPushModeCam(app.camHandler);
		//app.startPullMode(handler);
	}

	@Override
	public boolean isStreaming() {
		return isStreaming;
	}
	
	@Override
	public HResult restartStreaming() throws StreamingException {
		if(this.imageCallback == null) throw new StreamingException(Constants.RESTART_STREAM_EXCEP_MSG);
		return startStreaming(this.imageCallback);
	}

	@Override
	public HResult pauseStreaming() {
		HResult result = HResult.key(libToupcam.Toupcam_Pause(getCamHandler()));
		if(result.equals(HResult.S_OK) || result.equals(HResult.S_FALSE))
			isStreaming = false;

		return result;
	}

	@Override
	public HResult resumeStreaming() {
		HResult result = HResult.key(libToupcam.Toupcam_Pause(getCamHandler()));
		if(result.equals(HResult.S_OK) || result.equals(HResult.S_FALSE))
			isStreaming = true;

		return result;
	}

	@Override
	public HResult stopStreaming() {
		return HResult.key(libToupcam.Toupcam_Stop(getCamHandler()));
	}

	@Override
	public HResult getStillImage(int resolutionIndex) {
		return getSnapShot(getCamHandler(), resolutionIndex);
	}

	@Override
	public HResult setResolution(int resolutionIndex) {
		return HResult.key(libToupcam.Toupcam_put_eSize(getCamHandler(), resolutionIndex));
	}

	@Override
	public HResult startStreaming(ImageStreamCallback imageCallback) {
		isStreaming = true;
		this.imageCallback = imageCallback;		// caching imageCallback for later use, in case of start/restart
		
		int result = libToupcam.Toupcam_StartPushMode(getCamHandler(), 
				(Pointer imagePointer, Pointer imageMetaData, boolean isSnapshot) -> {

					ImageHeader header = ParserUtil.parseImageHeader(imageMetaData);
					BufferedImage image = Util.convertImagePointerToImage(
							imagePointer, header.getWidth(), header.getHeight());

					if(isSnapshot)
						imageCallback.onReceiveStillImage(image, header);						
					else imageCallback.onReceivePreviewImage(image, header); 
				}
				, Pointer.NULL);
		return HResult.key(result);
	}

	/*@Override
	public Toupcam getInstance() {
		return new App();
	}*/

	public void startPullMode(Pointer handler){
		HResult result = startPullWithCallBack(handler);
		System.out.println("Start Pull Result: " + result);
	}

	public void startPushModeCam(Pointer handler){
		HResult result = startPushMode(handler);
		System.out.println("Start Push Result: " + result);
	}

	public App(){
		//jFrame = createJFrame();
		libToupcam = (LibToupcam) getNativeLib();
		camHandler = openCam(null);
		//	Util.keepVMRunning();				// keep JVM from terminating, not needed inside tomcat.
	}

	private JFrame createJFrame(){
		JFrame frame = new JFrame();
		frame.setSize(1000, 750);
		frame.setResizable(false);
		frame.setVisible(true);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setTitle("Toupcam Java Wrapper");
		JLabel imageContainer = new JLabel();
		frame.add(imageContainer);
		return frame;
	}

	/**
	 * Checks for the machine's architecture and OS, load 
	 * and returns machine specific native library.
	 * 
	 * Machine Architecture: 32-bit or 64-bit
	 * OS: Linux or Windows
	 *  
	 * Note: To load native library JNA requires absolute path.
	 * @return
	 */
	private Object getNativeLib(){
		Object nativeLib;
		if(Platform.is64Bit()){
			if(Platform.isLinux())
				nativeLib = (LibToupcam) NativeUtils.loadLibrary(Constants.x64_TOUPCAM_SO, LibToupcam.class);
			else
				nativeLib = (LibToupcam) NativeUtils.loadLibrary(Constants.x64_TOUPCAM_DLL, LibToupcam.class);
		}else {
			if(Platform.isLinux())
				nativeLib = (LibToupcam) NativeUtils.loadLibrary(Constants.x86_TOUPCAM_SO, LibToupcam.class);
			else
				nativeLib = (LibToupcam) NativeUtils.loadLibrary(Constants.x86_TOUPCAM_DLL, LibToupcam.class);
		}
		return nativeLib;
	}

	public void registerPlugInOrOut(){
		libToupcam.Toupcam_HotPlug(new PTOUPCAM_HOTPLUG_CALLBACK() {
			@Override public void invoke() {
				System.out.println("Camera is pluged in or out.");
			}
		});
	}

	public RawFormat getRawFormat(Pointer handler){
		Pointer nFourCC = new Memory(4), bitdepth = new Memory(4);
		int result = libToupcam.Toupcam_get_RawFormat(handler, nFourCC, bitdepth);
		return new RawFormat(nFourCC.getInt(0), bitdepth.getInt(0), HResult.key(result));
	}

	public HResult setResolution(Pointer handler, int resolutionIndex){
		return HResult.key(libToupcam.Toupcam_put_eSize(handler, resolutionIndex));
	}

	@Override
	public int countConnectedCams() {
		Memory memory = new Memory(Constants.MEM_SIZE_FOR_TOUPCAMINST);
		return libToupcam.Toupcam_Enum(memory);
	}

	@Override
	public List<ToupcamInst> getToupcams() {
		List<ToupcamInst> toupcamInstList = new ArrayList<ToupcamInst>();
		Memory structurePointer = new Memory(Constants.MEM_SIZE_FOR_TOUPCAMINST);
		int count_cams = libToupcam.Toupcam_Enum(structurePointer);
		for(int i = 0 ; i < count_cams; i++) toupcamInstList.add(new ToupcamInst());


		toupcamInstList.forEach(toupcamInst -> {

			int structurePointerOffset = 0;
			toupcamInst.setDisplayName(structurePointer.getString(structurePointerOffset));
			structurePointerOffset += 64;
			toupcamInst.setId(structurePointer.getString(structurePointerOffset));
			structurePointerOffset += 64;

			Pointer modelPointer = structurePointer.getPointer(structurePointerOffset);
			int modelPointerOffset = 0;
			toupcamInst.setModel(new Model());

			toupcamInst.getModel().setName(modelPointer.getPointer(modelPointerOffset).getString(0));
			modelPointerOffset += Pointer.SIZE;
			toupcamInst.getModel().setFlag(modelPointer.getInt(modelPointerOffset));
			modelPointerOffset += Constants.INT_SIZE;
			toupcamInst.getModel().setMaxspeed(modelPointer.getInt(modelPointerOffset));
			modelPointerOffset += Constants.INT_SIZE;
			toupcamInst.getModel().setStill(modelPointer.getInt(modelPointerOffset));
			modelPointerOffset += Constants.INT_SIZE;
			toupcamInst.getModel().setPreview(modelPointer.getInt(modelPointerOffset));
			modelPointerOffset += Constants.INT_SIZE;

			int resolutions = (int) Math.max(toupcamInst.getModel().getPreview(),
					toupcamInst.getModel().getStill());

			Resolution[] resolutionArray = new Resolution[resolutions];
			for(int i = 0; i < resolutions; i++) resolutionArray[i] = new Resolution();

			toupcamInst.getModel().setRes(resolutionArray);
			Resolution[] toupcamInstRes = toupcamInst.getModel().getRes();

			for(int i = 0; i < resolutions; i++){
				toupcamInstRes[i].width = modelPointer.getInt(modelPointerOffset);
				modelPointerOffset += Constants.INT_SIZE;
				toupcamInstRes[i].height = modelPointer.getInt(modelPointerOffset);
				modelPointerOffset += Constants.INT_SIZE;
			}

		});

		return toupcamInstList;
	}

	public Pointer openCam(String id){
		camHandler = libToupcam.Toupcam_Open(id);
		return camHandler;
	}

	public HResult startPullWithCallBack(Pointer handler){
		int result = libToupcam.Toupcam_StartPullModeWithCallback(handler, new EventCallback() {
			@Override public void invoke(long event) {
				//System.out.println(Event.key(event) + " event received");
				if(Event.key(event) == Event.EVENT_STILLIMAGE){
					Image image = getStillImage(handler);
					System.out.println(image);
					Util.convertImagePointerToImage(image.getImagePointer(), image.getWidth(), image.getHeight());

				}else if(Event.key(event) == Event.EVENT_IMAGE){
					Image image = getImage(handler);
					System.out.println(image);
					//Util.convertImagePointerToImage(image.getImagePointer(), image.getWidth(), image.getHeight());
				}
			}
		}, 0);
		return HResult.key(result);
	}

	public HResult startPushMode(Pointer handler){
		int result = libToupcam.Toupcam_StartPushMode(handler, new PTOUPCAM_DATA_CALLBACK(){
			@Override public void invoke(Pointer imagePointer, Pointer imageMetaDataPointer, boolean isSnapshot) {
				ImageHeader header = ParserUtil.parseImageHeader(imageMetaDataPointer);
				System.out.println(header);
				Util.convertImagePointerToImage(imagePointer, 
						header.getWidth(), header.getHeight());  // 1280 * 960

				//JLabel label = (JLabel) jFrame.getComponent(0);
				//label.setIcon(new ImageIcon(image));
			}
		}, Pointer.NULL);
		return HResult.key(result);
	}

	public HResult setOptions(Pointer handler, Options option, int value){
		return HResult.key(libToupcam.Toupcam_put_Option(handler, option.getValue(), value));
	}

	public HResult getSnapShot(Pointer handler, int resolutionIndex){
		return HResult.key(libToupcam.Toupcam_Snap(handler, resolutionIndex));
	}

	public Image getImage(Pointer handler){
		//width=1280, height=960
		Pointer imageBuffer = new Memory(1280 * 960 * 4);
		Pointer width = new Memory(4), height = new Memory(4);
		int result = libToupcam.Toupcam_PullImage(handler, imageBuffer, 32, width, height);
		return new Image(imageBuffer, width.getInt(0), height.getInt(0),HResult.key(result));
	}

	public Image getStillImage(Pointer handler){
		//width=2592, height=1944	
		Pointer imageBuffer = new Memory(2592 * 1944);
		Pointer width = new Memory(4), height = new Memory(4);
		int result = libToupcam.Toupcam_PullStillImage(handler, imageBuffer, 8, width, height);
		return new Image(imageBuffer, width.getInt(0), height.getInt(0), HResult.key(result));
	}

	public Pointer getCamHandler() {return camHandler;}
	public void setCamHandler(Pointer camHandler) {this.camHandler = camHandler;}


}
