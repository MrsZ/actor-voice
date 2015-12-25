package com.example.voice;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import android.app.Fragment;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.example.voice.StreamDescriptionInterface.StreamState;

import org.json.JSONObject;

/**
 * simple fragment to display two rows of video streams
 */
public class FragmentVideoChat extends Fragment {
	// TODO replace this with your servers url!
	/** server url - where to request tokens */
	private String mTokenServerUrl = "https://wrtc-sample.actor.im:443";

	/** the licode signaling engine */
	VideoConnectorInterface mConnector = null;
	/** basic size of a video stream */
	Point mBasicViewSize = null;
	/** the container for all the videos */
	VideoGridLayout mContainer = null;
	/** the video streams view */
	VideoStreamsView mVsv = null;
	/** map of stream id -> video view */
	ConcurrentHashMap<String, VideoStreamPlaceholder> mVideoViews = new ConcurrentHashMap<String, VideoStreamPlaceholder>();

	/**
	 * click listener for the video views - will typically change the view from
	 * normal to zoomed mode
	 */
	private View.OnClickListener mVsvClickListener = new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			if (mContainer != null) {
				String streamId = ((VideoStreamPlaceholder) v).getStreamId();
				StreamDescriptionInterface stream = mConnector
						.getRemoteStreams().get(streamId);
				if (stream != null) {
					stream.setAudioActive(!stream.isAudioActive());
				}
			}
		}
	};

	/** the room observer instance for this fragment */
	private VideoConnectorInterface.RoomObserver mRoomObserver;
	/** the view to start casting video stream */
	private ImageView mStartCastView;

	/**
	 * create or retrieve a display element for given stream - will add this to
	 * the appropriate list and the container element for video streams.
	 *
	 * @param streamId
	 *            The source of the video data.
	 * @return An existing video display element, or a newly created one.
	 */
	protected VideoStreamPlaceholder makeVideoView(String streamId) {
		mVsv.addStream(streamId);
		if (mVideoViews.containsKey(streamId)) {
			return mVideoViews.get(streamId);
		} else if (getActivity() != null) {
			VideoStreamPlaceholder vsp = new VideoStreamPlaceholder(
					getActivity(), mVsv, streamId);
			vsp.setOnClickListener(mVsvClickListener);

			mVideoViews.put(streamId, vsp);
			mContainer.addView(vsp);
			return vsp;
		}

		// no activity? this is a dead fragment
		return null;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
//		Logging.enableTracing(
//				"logcat:",
//				EnumSet.of(Logging.TraceLevel.TRACE_ALL),
//				Logging.Severity.LS_SENSITIVE);
	}

	/** helper function - called to prepare the video chat connector instance */
	private void configureConnector() {
		mConnector = new LicodeConnector();
		mConnector.init(getActivity(), "user");

		mRoomObserver = new VideoConnectorInterface.RoomObserver() {
			@Override
			public void onStreamRemoved(final StreamDescriptionInterface stream) {
				if (stream == null || getActivity() == null
						|| mContainer == null) {
					return;
				}

				getActivity().runOnUiThread(new Runnable() {

					@Override
					public void run() {
						String streamId = stream.isLocal() ? VideoStreamsView.LOCAL_STREAM_ID
								: stream.getId();
						VideoStreamPlaceholder vsp = mVideoViews.get(streamId);
						if (vsp != null) {
							mVideoViews.remove(streamId);
							mContainer.removeView(vsp);
						}
						stream.detachRenderer();
						mVsv.removeStream(streamId);
						mConnector.destroy(stream);

						if (stream.isLocal()) {
							mStartCastView.setVisibility(mConnector
									.isPublishing() ? View.GONE : View.VISIBLE);
						}
					}
				});
			}

			@Override
			public void onStreamData(String message,
					StreamDescriptionInterface stream) {
				// ignored, for now
			}

			@Override
			public void onStreamAdded(final StreamDescriptionInterface stream) {
				if (getActivity() == null) {
					return;
				}
				getActivity().runOnUiThread(new Runnable() {

					@Override
					public void run() {
						mConnector.subscribe(stream);
					}
				});
			}

			@Override
			public void onRoomDisconnected() {
				if (getActivity() == null) {
					return;
				}
				getActivity().runOnUiThread(new Runnable() {
					@Override
					public void run() {
						for (String key : mVideoViews.keySet()) {
							VideoStreamPlaceholder vsp = mVideoViews.get(key);
							if (vsp != null) {
								mContainer.removeView(vsp);
							}
							mVsv.removeStream(key);
						}
						mVideoViews.clear();
						mVsv.onPause();

						if (getActivity() != null) {
							getActivity().invalidateOptionsMenu();
						}
					}
				});
			}

			@Override
			public void onRoomConnected(
					Map<String, StreamDescriptionInterface> streamList) {
				mConnector.setBandwidthLimits(84, 16);

				if (getActivity() == null) {
					return;
				}

				// getActivity().supportInvalidateOptionsMenu();

				getActivity().runOnUiThread(new Runnable() {

					@Override
					public void run() {
						// TODO: Disconnect enable?
						if (getActivity() != null) {
							getActivity().invalidateOptionsMenu();
							getActivity().invalidateOptionsMenu();
							mConnector.requestPublish();
						}
					}
				});
			}

			@Override
			public void onStreamMediaAvailable(
					final StreamDescriptionInterface stream) {
				if (mContainer == null) {
					return;
				}
				mContainer.post(new Runnable() {
					@Override
					public void run() {
						if (mContainer == null) {
							return;
						}
						makeVideoView(stream.getId());
						mConnector.attachRenderer(stream, mVsv);
					}
				});
			}

			@Override
			public void onPublishAllowed() {
				startPublish();
			}

			@Override
			public void onRequestRefreshToken() {
				startRefreshToken();
			}
		};
		mConnector.addObserver(mRoomObserver);
	}

	@Override
	public void onStart() {
		super.onStart();

		if (mConnector != null && mConnector.isConnected()
				&& mContainer != null) {
			mContainer.setCollapsed(false);
			mContainer.post(new Runnable() {

				@Override
				public void run() {
					if (getView() == null || getActivity() == null) {
						return;
					}
					Map<String, StreamDescriptionInterface> streams = mConnector
							.getRemoteStreams();
					for (String key : streams.keySet()) {
						StreamDescriptionInterface stream = streams.get(key);
						if (stream != null
								&& stream.getState() != StreamState.CLOSING) {
							mConnector.subscribe(stream);
						}
					}

					if (mConnector.isPublishing()) {
						makeVideoView(VideoStreamsView.LOCAL_STREAM_ID);
						mConnector.attachLocalStream(mVsv);
					}
				}
			});
		}
//		startVideoChat(); // lets start Video Chat Immediately. Only for testing
	}

	@Override
	public void onStop() {
		super.onStop();

		if (mConnector != null) {
			Map<String, StreamDescriptionInterface> streams = mConnector
					.getRemoteStreams();
			for (String key : mVideoViews.keySet()) {
				StreamDescriptionInterface stream = streams.get(key);
				if (stream != null) {
					stream.detachRenderer();
				}
			}

			mConnector.detachLocalStream();

			// TODO dk: check if detaching is necessary if we cut connection
			// entirely?
			mConnector.disconnect();
		}
	}

	@Override
	public void onPause() {
		super.onPause();

		mVsv.onPause();
		// mConnector.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();

		mVsv.onResume();
		// mConnector.onResume();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		configureConnector();
		setHasOptionsMenu(true);

		FrameLayout cont = (FrameLayout) inflater.inflate(R.layout.fragment_videochat, container, false);
		Button b = (Button) cont.findViewById(R.id.start);
		b.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mConnector.isConnected()) {
					mConnector.detachLocalStream();
					mConnector.disconnect();
				} else {
					startVideoChat();
					Toast.makeText(getActivity(), "Start", Toast.LENGTH_LONG).show();
				}
			}
		});
		View mainView = cont.findViewById(R.id.videochat_grid);

		mContainer = (VideoGridLayout) mainView
				.findViewById(R.id.videochat_grid);
		mContainer.setCollapsed(false);

		if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
			mContainer.setGridDimensions(9, 1);
		} else {
			mContainer.setGridDimensions(3, 3);
		}

		mVsv = new VideoStreamsView(this.getActivity());
		mContainer.addView(mVsv);
		mContainer.setVideoElement(mVsv);

		final ImageView startCastView = new ImageView(getActivity());
		mStartCastView = startCastView;
//		startCastView.setImageResource(R.drawable.streamself);
		startCastView.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				if (mConnector == null || !mConnector.isConnected()) {
					Toast.makeText(getActivity(),
							R.string.videochatFailNotConnected,
							Toast.LENGTH_LONG).show();
					return;
				}
//				v.postDelayed(new Runnable() {
//					public void run() {
//						if (mConnector != null && mConnector.isConnected()) {
//							mConnector.requestPublish();
//						}
//					}
//				}, 100L); // TODO dk: hardcoded delay!
				Toast toast = Toast.makeText(getActivity(),
						R.string.videochatWaitConnecting, Toast.LENGTH_SHORT);
				toast.setGravity(Gravity.CENTER, 0, 0);
				toast.show();
			}
		});
		mContainer.addView(startCastView);
		startCastView.setVisibility(mConnector.isPublishing() ? View.GONE
				: View.VISIBLE);
		startCastView.setLayoutParams(new VideoGridLayout.LayoutParams(
				Integer.MAX_VALUE));

		return cont;
	}




	/** instantiate a new fragment */
	public static FragmentVideoChat createInstance(Bundle extras) {
		FragmentVideoChat result = new FragmentVideoChat();
		result.setArguments(extras);
		return result;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		if (mConnector != null) {
			mConnector.removeObserver(mRoomObserver);
		}
	}

	/** publish */
	protected void startPublish() {
		getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (mConnector != null && mConnector.isConnected()) {
					if (mContainer != null) {
						/* VideoStreamPlaceholder placeholder = */
						makeVideoView(VideoStreamsView.LOCAL_STREAM_ID);
					}

					mStartCastView.setVisibility(View.GONE);

					mConnector.publish(mVsv);
				}
			}
		});
	}

	/** request a new token - to initialize first connection */
	private void startVideoChat() {
		// begin connection process
		FutureResult<String> response = new FutureResult<String>();
		response.addResultObserver(new FutureResult.OnResultCallback<String>() {
			@Override
			public void OnResult(String result) {
//				mConnector.connect(result);
//
				Log.d("result", "" + result);
				try{
					JSONObject object = new JSONObject(result);
					String token = object.getString("token");
					String roomid = object.getString("roomId");
					Log.d("RoomId", roomid);
					mConnector.connect(token);
				}catch (Exception e){
					e.printStackTrace();
				}

			}
		});
		new XmlHttpRequest(response).execute(mTokenServerUrl);
	}

	/** request a new token - to keep connected */
	private void startRefreshToken() {
		FutureResult<String> response = new FutureResult<String>();
		response.addResultObserver(new FutureResult.OnResultCallback<String>() {
			@Override
			public void OnResult(String result) {
				mConnector.refreshVideoToken(result);
			}
		});
		new XmlHttpRequest(response).execute(mTokenServerUrl);
	}

	/** helper class - fake xml http request */
	private static class XmlHttpRequest extends AsyncTask<String, Void, String> {
		FutureResult<String> mResponse;

		public XmlHttpRequest(FutureResult<String> futureResponse) {
			mResponse = futureResponse;
		}

		@Override
		protected String doInBackground(String... params) {
			String response = null;
			HttpURLConnection conn = null;
			InputStream is = null;
			OutputStream os = null;

			try {
				URL url = new URL(params[0] + "/tokens");
				String id = "75555555555";
				String message;
				JSONObject data = new JSONObject();
				data.put("id", id);
				data.put("authHash", "0465c1174ad115ebdfc47695579d5a18425d8220817f1169dd7f5402394c5370");
				data.put("roomId", "56687f12da186210281074fa");
				message = data.toString();
				Log.d("JSON", message);
				conn = (HttpURLConnection) url.openConnection();

				conn.setRequestMethod("POST");
				conn.setDoInput(true);
				conn.setDoOutput(true);
				conn.setFixedLengthStreamingMode(message.getBytes().length);

//				if (Build.VERSION.SDK_INT > 13) {
//					conn.setRequestProperty("Connection", "close");
//				}

				conn.setRequestProperty("Content-Type",
						"application/json");
//				conn.setRequestProperty("X-Request-With", "XMLHttpRequest");

//				conn.connect();

				conn.setDoOutput(true);
//					conn.setChunkedStreamingMode(0);

				OutputStream out = new BufferedOutputStream(conn.getOutputStream());
				out.write(message.getBytes("UTF-8"));
				out.flush();
				out.close();
				InputStream in = new BufferedInputStream(conn.getInputStream());
				response = readStream(in);
				in.close();

//					response = conn.getResponseMessage();
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				conn.disconnect();
			}
			return response;
		}

		private String readStream(InputStream is) {
			java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
			return s.hasNext() ? s.next() : "";
		}

		@Override
		protected void onPostExecute(String result) {
			if (mResponse != null) {
				mResponse.setResult(result);
				mResponse = null;
			}
		}
	}

	public static String sha256(String base) {
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA-256");

			md.update(base.getBytes("UTF-8"));

			byte byteData[] = md.digest();

			//convert the byte to hex format method 1
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < byteData.length; i++) {
				sb.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
			}

			return sb.toString();

		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return "";
	}

}
