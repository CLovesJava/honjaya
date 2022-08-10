package com.ssafy.honjaya.api.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import com.ssafy.honjaya.api.request.MeetingReq;
import com.ssafy.honjaya.api.response.MeetingRes;

@Service
public class MeetingServiceImpl implements MeetingService {
	private static final Logger logger = LoggerFactory.getLogger(MeetingServiceImpl.class);
	
	private static final int SUCCESS = 1;
	private static final int CANCEL = 0;
	private static final int TIMEOUT = -1;

	private ReentrantReadWriteLock lock;
	
//	private Map<MeetingReq, DeferredResult<MeetingRes>> waitingUsers;
	private List<Map<MeetingReq, DeferredResult<MeetingRes>>> waitingUsers;
	private List<Map<MeetingReq, DeferredResult<MeetingRes>>> waitingUsersOfCommanders;
	
	private List<Map<MeetingReq, DeferredResult<MeetingRes>>> waitingAvatar;
	private List<Map<MeetingReq, DeferredResult<MeetingRes>>> waitingCommander;
	
	private List<Map<MeetingReq, MeetingReq>> waitingPair;
	
	// {key : websocket session id, value : room id}
	private Map<String, String> connectedUsers;
	
	
	private Map<String, Map<MeetingReq, DeferredResult<MeetingRes>>> whereByUserNo;
	private Map<String, MeetingReq> userNoMeetingReq; // 0 or 1
	// 나중에 String -> Integer로 바꿔야 함
	

//	@Autowired
//	private UserRepository userRepository;

	@PostConstruct
	private void setUp() {
		this.waitingUsers = new ArrayList<>();
		this.waitingUsersOfCommanders = new ArrayList<>();
		this.waitingAvatar = new ArrayList<>();
		this.waitingCommander = new ArrayList<>();
		this.waitingPair = new ArrayList<>();
		this.whereByUserNo = new HashMap<>();
		this.userNoMeetingReq = new HashMap<>();
		for (int i = 0; i < 2; i++) {
			this.waitingUsers.add(new LinkedHashMap<>());
			this.waitingUsersOfCommanders.add(new LinkedHashMap<>());
			this.waitingAvatar.add(new LinkedHashMap<>());
			this.waitingCommander.add(new LinkedHashMap<>());
			this.waitingPair.add(new LinkedHashMap<>());
		}
		this.lock = new ReentrantReadWriteLock();
		this.connectedUsers = new ConcurrentHashMap<>();
	}

	@Override
	@Async("asyncThreadPool")
	public void readyMeeting(MeetingReq meetingReq, DeferredResult<MeetingRes> deferredResult) {
		logger.info("## Join room request. {}[{}]", Thread.currentThread().getName(), Thread.currentThread().getId());
		if (meetingReq == null || deferredResult == null) {
			return;
		}

		try {
			lock.writeLock().lock();
			
			for (int i = 0; i < 2; i++) { // i가 0일 때는 2인, i가 1일 때는 4인
				if (meetingReq.getTotal() == (i + 1) * 2) {
					// 아바타나 지시자일 경우 서로 짝 먼저 지어줘야 함
					switch (meetingReq.getRoleCode()) {
					case 1:
						waitingUsers.get(i).put(meetingReq, deferredResult);
						whereByUserNo.put(meetingReq.getSessionId(), waitingUsers.get(i));
						userNoMeetingReq.put(meetingReq.getSessionId(), meetingReq);
						break;
					case 2: // 아바타
						if (waitingCommander.get(i).size() > 0) { // 기다리고 있는 지시자가 있을 경우 꺼내옴
							MeetingReq commanderReq = waitingCommander.get(i).keySet().iterator().next();
							DeferredResult<MeetingRes> commanderResult = waitingCommander.get(i).remove(commanderReq);
							waitingPair.get(i).put(meetingReq, commanderReq);
							waitingPair.get(i).put(commanderReq, meetingReq);
							
							waitingUsers.get(i).put(meetingReq, deferredResult); // 아바타는 유저 대기 맵에 저장
							whereByUserNo.put(meetingReq.getSessionId(), waitingUsers.get(i));
							
							waitingUsersOfCommanders.get(i).put(commanderReq, commanderResult); // 지시자는 지시자용 대기 맵에 저장
							whereByUserNo.put(commanderReq.getSessionId(), waitingUsersOfCommanders.get(i));
						} else {
							waitingAvatar.get(i).put(meetingReq, deferredResult);
							whereByUserNo.put(meetingReq.getSessionId(), waitingAvatar.get(i));
						}
						userNoMeetingReq.put(meetingReq.getSessionId(), meetingReq);
						break;
					case 3: // 지시자
						if (waitingAvatar.get(i).size() > 0) { // 기다리고 있는 아바타가 있을 경우 꺼내옴
							MeetingReq avatarReq = waitingAvatar.get(i).keySet().iterator().next();
							DeferredResult<MeetingRes> avatarResult = waitingAvatar.get(i).remove(avatarReq);
							waitingPair.get(i).put(meetingReq, avatarReq);
							waitingPair.get(i).put(avatarReq, meetingReq);
							
							waitingUsers.get(i).put(avatarReq, avatarResult); // 아바타는 유저 대기 맵에 저장
							whereByUserNo.put(avatarReq.getSessionId(), waitingUsers.get(i));
							
							waitingUsersOfCommanders.get(i).put(meetingReq, deferredResult); // 지시자는 지시자용 대기 맵에 저장
							whereByUserNo.put(meetingReq.getSessionId(), waitingUsersOfCommanders.get(i));
						} else {
							waitingCommander.get(i).put(meetingReq, deferredResult);
							whereByUserNo.put(meetingReq.getSessionId(), waitingCommander.get(i));
						}
						userNoMeetingReq.put(meetingReq.getSessionId(), meetingReq);
						break;
					default:
						logger.error("Wrong Role Code!!!");
						break;
					}
				}
			}
			
//			waitingUsers.put(meetingReq, deferredResult);
		} finally {
			lock.writeLock().unlock();
			establishRoom();
		}

	}

	@Override
	public void cancelChatRoom(MeetingReq meetingReq) {
		try {
			System.out.println("cancelChatRoom() 호출! before lock");
			lock.writeLock().lock();
			if (!userNoMeetingReq.containsKey(meetingReq.getSessionId())) {
				return;
			}
			meetingReq = userNoMeetingReq.get(meetingReq.getSessionId());
			DeferredResult<MeetingRes> result = cancelAndMakeResult(meetingReq);
			setJoinResult(result, new MeetingRes(null, meetingReq.getSessionId(), 0, 0, 0, CANCEL, null));
		} finally {
			lock.writeLock().unlock();
			System.out.println("cancelChatRoom() 호출! after lock");
		}
	}

	@Override
	public void timeout(MeetingReq meetingReq) {
		try {
			System.out.println("timeout() 호출! before lock");
			lock.writeLock().lock();
			if (!userNoMeetingReq.containsKey(meetingReq.getSessionId())) {
				return;
			}
			meetingReq = userNoMeetingReq.get(meetingReq.getSessionId());
			DeferredResult<MeetingRes> result = cancelAndMakeResult(meetingReq);
			setJoinResult(result, new MeetingRes(null, meetingReq.getSessionId(), 0, 0, 0, TIMEOUT, null));
		} finally {
			lock.writeLock().unlock();
			System.out.println("timeout() 호출! after lock");
		}
	}

	@Override
	public void connectUser(String chatRoomId, String websocketSessionId) {
		connectedUsers.put(websocketSessionId, chatRoomId);
	}

	@Override
	public void disconnectUser(String websocketSessionId) {
		System.out.println("떠났습니다.");
	}

	private void establishRoom() {
		try {
//			logger.debug("Current waiting users : " + waitingUsers.size());
			lock.readLock().lock();
			
			for (int i = 0; i < 2; i++) {
				if (waitingUsers.get(i).size() >= (i + 1) * 2) {
					if (waitingUsersOfCommanders.get(i).size() * 2 != waitingPair.get(i).size()) {
						// 페어가 유지되지 않고 깨졌을 경우 return (cancelChatRoom 또는 timeout이 작동되기 전에 깨졌을 경우에 대비한 코드)
						continue;
					}
					String uuid = UUID.randomUUID().toString();

					Iterator<MeetingReq> itr = waitingUsers.get(i).keySet().iterator();
					MeetingReq[] reqs = new MeetingReq[(i + 1) * 2];
					for (int j = 0; j < (i + 1) * 2; j++) {
						reqs[j] = itr.next();
					}
					
					for (int j = 0; j < (i + 1) * 2; j++) {
						MeetingRes res = new MeetingRes();
						res.setUuid(uuid);
						res.setSessionId(reqs[j].getSessionId());
						res.setResult(SUCCESS);
						
						whereByUserNo.remove(reqs[j].getSessionId());
						userNoMeetingReq.remove(reqs[j].getSessionId());
						
						waitingUsers.get(i).remove(reqs[j]).setResult(res);
						
						if (reqs[j].getRoleCode() == 2) {
							MeetingReq commanderReq = waitingPair.get(i).remove(reqs[j]);
							waitingPair.get(i).remove(commanderReq);
							MeetingRes commanderRes = new MeetingRes();
							commanderRes.setUuid(uuid);
							commanderRes.setSessionId(commanderReq.getSessionId());
							commanderRes.setResult(SUCCESS);
							
							whereByUserNo.remove(commanderReq.getSessionId());
							userNoMeetingReq.remove(commanderReq.getSessionId());
							
							waitingUsersOfCommanders.get(i).remove(commanderReq).setResult(commanderRes);
						}
					}
					return;
				}
			}
		} catch (Exception e) {
			logger.warn("Exception occur while checking waiting users", e);
		} finally {
			lock.readLock().unlock();
		}
	}
	
	private DeferredResult<MeetingRes> cancelAndMakeResult(MeetingReq meetingReq) {
		DeferredResult<MeetingRes> result;
		
		int index = meetingReq.getTotal() / 2 - 1;
		
		Map<MeetingReq, DeferredResult<MeetingRes>> where = whereByUserNo.get(meetingReq.getSessionId());
		
		result = where.remove(meetingReq);
		whereByUserNo.remove(meetingReq.getSessionId());
		userNoMeetingReq.remove(meetingReq.getSessionId());
		
		if (waitingPair.get(index).containsKey(meetingReq)) { // Cancel 할 때 이미 페어가 정해져 있었을 경우
			MeetingReq pairReq = waitingPair.get(index).remove(meetingReq);
			waitingPair.get(index).remove(pairReq);
			DeferredResult<MeetingRes> pairResult = whereByUserNo.get(pairReq.getSessionId()).remove(pairReq);
			
			if (meetingReq.getRoleCode() == 3) { // Cancel 한 사람이 Commander일 경우, Avatar를 다시 대기열로
				
				if (waitingCommander.get(index).size() > 0) { // 기다리고 있는 지시자가 있을 경우 꺼내옴
					MeetingReq commanderReq = waitingCommander.get(index).keySet().iterator().next();
					DeferredResult<MeetingRes> commanderResult = waitingCommander.get(index).remove(commanderReq);
					waitingPair.get(index).put(pairReq, commanderReq);
					waitingPair.get(index).put(commanderReq, pairReq);
					
					waitingUsers.get(index).put(pairReq, pairResult); // 아바타는 유저 대기 맵에 저장
					whereByUserNo.put(pairReq.getSessionId(), waitingUsers.get(index));
					
					waitingUsersOfCommanders.get(index).put(commanderReq, commanderResult); // 지시자는 지시자용 대기 맵에 저장
					whereByUserNo.put(commanderReq.getSessionId(), waitingUsersOfCommanders.get(index));
				} else {
					waitingAvatar.get(index).put(pairReq, pairResult);
					whereByUserNo.put(pairReq.getSessionId(), waitingAvatar.get(index));
				}
				
			} else if (meetingReq.getRoleCode() == 2) { // Cancel 한 사람이 Avatar일 경우, Commander를 다시 대기열로
				
				if (waitingAvatar.get(index).size() > 0) { // 기다리고 있는 아바타가 있을 경우 꺼내옴
					MeetingReq avatarReq = waitingAvatar.get(index).keySet().iterator().next();
					DeferredResult<MeetingRes> avatarResult = waitingAvatar.get(index).remove(avatarReq);
					waitingPair.get(index).put(pairReq, avatarReq);
					waitingPair.get(index).put(avatarReq, pairReq);
					
					waitingUsers.get(index).put(avatarReq, avatarResult); // 아바타는 유저 대기 맵에 저장
					whereByUserNo.put(avatarReq.getSessionId(), waitingUsers.get(index));
					
					waitingUsersOfCommanders.get(index).put(pairReq, pairResult); // 지시자는 지시자용 대기 맵에 저장
					whereByUserNo.put(pairReq.getSessionId(), waitingUsersOfCommanders.get(index));
				} else {
					waitingCommander.get(index).put(pairReq, pairResult);
					whereByUserNo.put(pairReq.getSessionId(), waitingCommander.get(index));
				}
			}
		}
		return result;
		
//		switch (meetingReq.getRoleCode()) {
//		case 1:
//			result = waitingUsers.get(index).remove(meetingReq);
//			break;
//		case 2:
//			result = waitingUsers.get(index).remove(meetingReq);
//			if (result == null) {
//				result = waitingAvatar.get(index).remove(meetingReq);
//			} else {
//				MeetingReq pair = waitingPair.get(index).remove(meetingReq);
//				waitingPair.get(index).remove(pair);
//			}
//			break;
//		case 3:
//			result = waitingUsersOfCommanders.get(index).remove(meetingReq);
//			if (result == null) {
//				result = waitingCommander.get(index).remove(meetingReq);
//			} else {
//				MeetingReq pair = waitingPair.get(index).remove(meetingReq);
//				waitingPair.get(index).remove(pair);
//			}
//			break;
//		default:
//			break;
//		}
//		return result;
	}
	
//	private MeetingReq findRealMeetingReq(MeetingReq meetingReq) {
//		for (int i = 0; i < 2; i++) {
//			if (waitingUsers.get(i).containsKey(meetingReq)) {
//				
//			}
//		}
//		
//		this.waitingUsers.add(new LinkedHashMap<>());
//		this.waitingUsersOfCommanders.add(new LinkedHashMap<>());
//		this.waitingAvatar.add(new LinkedHashMap<>());
//		this.waitingCommander.add(new LinkedHashMap<>());
//		this.waitingPair.add(new LinkedHashMap<>());
//	}

	private void setJoinResult(DeferredResult<MeetingRes> result, MeetingRes response) {
		if (result != null) {
			result.setResult(response);
		}
	}
}